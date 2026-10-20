package com.spawnlau.gemmapocket;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A pinned, resumable HTTPS download. Only verified bytes become the installed model. */
final class ModelDownloader {
    static final String FILENAME="embeddinggemma-2-text-vision-440m.litertlm";
    static final String SHA256="92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0";
    static final long BYTES=387710976L;
    static final String SOURCE="https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/e301f74d5551b0c2641bd5cb4652a76239d5c5f8/"+FILENAME;
    static final String MIRROR=SOURCE.replace("https://huggingface.co/","https://hf-mirror.com/");
    private static final Pattern RANGE=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    interface Listener {
        default void connecting(String host) {}
        void progress(long received,long total);
        void verifying();
    }

    static final class Cancellation {
        private volatile boolean cancelled;
        private HttpURLConnection connection;
        void cancel() {
            HttpURLConnection current;
            synchronized(this) {cancelled=true;current=connection;}
            // Android's disconnect can wait for a blocked socket. Never wait for it on the UI thread.
            if(current!=null) java.util.concurrent.CompletableFuture.runAsync(current::disconnect);
        }
        boolean isCancelled() {return cancelled;}
        void check() throws InterruptedIOException {
            if(cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download paused");
        }
        synchronized void attach(HttpURLConnection value) throws InterruptedIOException {
            check(); connection=value;
        }
        synchronized void detach(HttpURLConnection value) {if(connection==value) connection=null;}
    }

    private final URL[] sources;
    private final long expectedBytes;
    private final String expectedHash;
    private final java.net.Proxy proxy;

    ModelDownloader() {this((java.net.Proxy)null);}

    ModelDownloader(java.net.Proxy proxy) {
        try {sources=new URL[]{new URL(SOURCE),new URL(MIRROR)};} catch(java.net.MalformedURLException e) {throw new IllegalStateException(e);}
        expectedBytes=BYTES; expectedHash=SHA256;this.proxy=proxy;
    }

    // Package-private seam for deterministic transport tests, with the same HTTPS requirement.
    ModelDownloader(URL source,long expectedBytes,String expectedHash) {
        this(new URL[]{source},expectedBytes,expectedHash);
    }

    ModelDownloader(URL[] sources,long expectedBytes,String expectedHash) {
        this(sources,expectedBytes,expectedHash,null);
    }

    ModelDownloader(URL[] sources,long expectedBytes,String expectedHash,java.net.Proxy proxy) {
        this.sources=sources; this.expectedBytes=expectedBytes; this.expectedHash=expectedHash;this.proxy=proxy;
    }

    void download(File destination,Listener listener,Cancellation cancellation) throws IOException {
        File directory=destination.getParentFile();
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create model storage");
        // Activity recreation may briefly overlap the previous worker. Never share a partial writer.
        try(RandomAccessFile lockFile=new RandomAccessFile(new File(directory,destination.getName()+".download.lock"),"rw")) {
            try(FileLock ignored=acquireLock(lockFile,cancellation)) {
                cancellation.check();
                if(destination.isFile() && destination.length()==expectedBytes) return;
                File partial=new File(directory,destination.getName()+".download.part");
                if(partial.length()>expectedBytes) Files.delete(partial.toPath());
                for(int attempt=0;attempt<sources.length;attempt++) {
                    cancellation.check();
                    long offset=partial.length();
                    if(directory.getUsableSpace()<expectedBytes-offset+16*1024*1024L) throw new IOException("Not enough space. Free at least 410 MB and try again.");
                    listener.progress(offset,expectedBytes);
                    try {
                        if(offset<expectedBytes) {
                            listener.connecting(sources[attempt].getHost());
                            transfer(sources[attempt],partial,offset,listener,cancellation);
                        }
                        listener.verifying();
                        installVerified(partial,destination,cancellation);
                        return;
                    } catch(IOException error) {
                        cancellation.check();
                        if(attempt==sources.length-1) throw new IOException("Download could not be completed. Check your connection and tap Retry to resume.",error);
                    }
                }
            }
        }
    }

    private static FileLock acquireLock(RandomAccessFile file,Cancellation cancellation) throws IOException {
        while(true) {
            cancellation.check();
            try {
                FileLock lock=file.getChannel().tryLock();
                if(lock!=null) return lock;
            } catch(java.nio.channels.OverlappingFileLockException ignored) { /* Previous Activity is stopping. */ }
            try {Thread.sleep(100);} catch(InterruptedException e) {Thread.currentThread().interrupt();cancellation.check();}
        }
    }

    private void transfer(URL source,File partial,long offset,Listener listener,Cancellation cancellation) throws IOException {
        URL current=source;
        for(int redirects=0;redirects<=8;redirects++) {
            cancellation.check();
            if(!"https".equalsIgnoreCase(current.getProtocol())) throw new IOException("The model download requires HTTPS");
            HttpURLConnection connection=(HttpURLConnection)(proxy==null?current.openConnection():current.openConnection(proxy));
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent","GemmaPocket/0.3");
            connection.setRequestProperty("Accept-Encoding","identity");
            if(offset>0) connection.setRequestProperty("Range","bytes="+offset+"-");
            try {
                cancellation.attach(connection);
                int code=connection.getResponseCode();
                if(code==301 || code==302 || code==303 || code==307 || code==308) {
                    String location=connection.getHeaderField("Location");
                    if(location==null) throw new IOException("The download redirect has no destination");
                    current=new URL(current,location); continue;
                }
                long received=offset;
                if(code==HttpURLConnection.HTTP_OK) {
                    // Some servers ignore Range. Replace the partial instead of appending duplicate bytes.
                    received=0;
                } else if(code==HttpURLConnection.HTTP_PARTIAL) {
                    String header=connection.getHeaderField("Content-Range");
                    Matcher range=RANGE.matcher(header==null?"":header);
                    if(!range.matches() || Long.parseLong(range.group(1))!=offset || Long.parseLong(range.group(2))!=expectedBytes-1 || Long.parseLong(range.group(3))!=expectedBytes)
                        throw new IOException("The server returned an unexpected download range. Please retry.");
                } else throw new IOException("Model download returned HTTP "+code+". Check your connection and retry.");
                long length=connection.getContentLengthLong();
                if(length>=0 && length!=expectedBytes-received) throw new IOException("The server returned an unexpected model size");
                if(partial.getParentFile().getUsableSpace()<expectedBytes-received+16*1024*1024L) throw new IOException("Not enough space to download the model");
                cancellation.check();
                long lastUpdate=0;
                try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(partial,received>0)) {
                    byte[] buffer=new byte[256*1024]; int count;
                    while((count=in.read(buffer))!=-1) {
                        cancellation.check();
                        if(received+count>expectedBytes) throw new IOException("The download is larger than the pinned model");
                        out.write(buffer,0,count); received+=count;
                        long now=System.nanoTime();
                        if(now-lastUpdate>=200_000_000L || received==expectedBytes) {listener.progress(received,expectedBytes);lastUpdate=now;}
                    }
                    out.getFD().sync();
                }
                cancellation.check();
                if(received!=expectedBytes) throw new IOException("Download interrupted. Retry to resume the saved progress.");
                return;
            } finally {
                cancellation.detach(connection); connection.disconnect();
            }
        }
        throw new IOException("Too many model download redirects. Please retry.");
    }

    void installVerified(File partial,File destination,Cancellation cancellation) throws IOException {
        cancellation.check();
        if(partial.length()!=expectedBytes) throw new IOException("Unexpected file size. Use the 440M Text-Vision model.");
        MessageDigest digest;
        try {digest=MessageDigest.getInstance("SHA-256");} catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        try(InputStream in=new FileInputStream(partial)) {
            byte[] buffer=new byte[1024*1024];int count;
            while((count=in.read(buffer))!=-1) {cancellation.check();digest.update(buffer,0,count);}
        }
        StringBuilder hex=new StringBuilder();
        for(byte value:digest.digest()) hex.append(String.format(Locale.ROOT,"%02x",value&255));
        if(!expectedHash.contentEquals(hex)) {
            Files.delete(partial.toPath());
            throw new IOException("Model verification failed. Retry to download a fresh copy.");
        }
        cancellation.check();
        Files.move(partial.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
