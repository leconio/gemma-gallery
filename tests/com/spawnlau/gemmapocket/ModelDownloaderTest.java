package com.spawnlau.gemmapocket;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Transport failure tests run against the production downloader without downloading large weights. */
public final class ModelDownloaderTest {
    private static final byte[] MODEL=new byte[600123];
    private static int passed;
    static {new Random(42).nextBytes(MODEL);}
    private static final ModelDownloader.Listener QUIET=new ModelDownloader.Listener() {
        public void progress(long count,long total) {}
        public void verifying() {}
    };

    public static void main(String[] args) throws Exception {
        test("fresh HTTPS redirect",()->{
            Fixture f=new Fixture();f.add(302,new byte[0],Map.of("Location","https://cdn.invalid/model"));f.add(200,MODEL,Map.of());
            f.download();f.installed();check(f.requests.size()==2,"redirect not followed");
        });
        test("Range survives redirect",()->{
            Fixture f=new Fixture();f.partial(1234);f.add(307,new byte[0],Map.of("Location","https://cdn.invalid/model"));f.range(1234);
            f.download();f.installed();check(f.requests.stream().allMatch(x->"bytes=1234-".equals(x.range)),"Range was lost");
        });
        test("alternate source resumes the same pinned file",()->{
            Fixture f=new Fixture();f.partial(1234);f.add(503,new byte[0],Map.of());f.range(1234);
            ModelDownloader fallback=new ModelDownloader(new URL[]{f.source,f.source},MODEL.length,hash(MODEL));
            fallback.download(f.target,QUIET,new ModelDownloader.Cancellation());f.installed();check(f.requests.size()==2,"alternate source not tried");
        });
        test("explicit acceptance proxy keeps HTTPS verification path",()->{
            Fixture f=new Fixture();f.add(200,MODEL,Map.of());
            Proxy proxy=new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",48765));
            new ModelDownloader(new URL[]{f.source},MODEL.length,hash(MODEL),proxy).download(f.target,QUIET,new ModelDownloader.Cancellation());
            f.installed();check(proxy.equals(f.selectedProxy),"explicit proxy not used");
        });
        test("server ignoring Range restarts safely",()->{
            Fixture f=new Fixture();f.partial(1234);f.add(200,MODEL,Map.of());f.download();f.installed();
        });
        test("truncated response resumes saved bytes",()->{
            Fixture f=new Fixture();f.add(200,Arrays.copyOf(MODEL,12345),Map.of("Content-Length",String.valueOf(MODEL.length)));
            expectFailure(f::download);check(!f.target.exists() && f.part.length()==12345,"incomplete bytes were published or lost");
            f.range(12345);f.download();f.installed();
        });
        test("pause saves progress and resumes",()->{
            Fixture f=new Fixture();f.add(200,MODEL,Map.of());ModelDownloader.Cancellation cancel=new ModelDownloader.Cancellation();
            expectFailure(()->f.downloader.download(f.target,new ModelDownloader.Listener() {
                public void progress(long count,long total) {if(count>0)cancel.cancel();}
                public void verifying() {}
            },cancel));
            int saved=(int)f.part.length();check(saved>0 && saved<MODEL.length && !f.target.exists(),"pause did not preserve a partial");
            f.range(saved);f.download();f.installed();
        });
        test("complete partial is verified without network",()->{
            Fixture f=new Fixture();f.partial(MODEL.length);f.download();f.installed();check(f.requests.isEmpty(),"complete partial was downloaded again");
        });
        test("checksum mismatch never installs",()->{
            Fixture f=new Fixture();byte[] bad=MODEL.clone();bad[0]^=1;f.add(200,bad,Map.of());
            expectFailure(f::download);check(!f.target.exists() && !f.part.exists(),"corrupt bytes were kept as a model");
        });
        test("invalid import preserves existing model",()->{
            Fixture f=new Fixture();Files.write(f.target.toPath(),MODEL);byte[] bad=MODEL.clone();bad[0]^=1;Files.write(f.part.toPath(),bad);
            expectFailure(()->f.downloader.installVerified(f.part,f.target,new ModelDownloader.Cancellation()));
            check(Arrays.equals(Files.readAllBytes(f.target.toPath()),MODEL),"invalid import replaced the good model");
        });
        test("bad Content-Range is rejected",()->{
            Fixture f=new Fixture();f.partial(1234);f.add(206,Arrays.copyOfRange(MODEL,1234,MODEL.length),Map.of("Content-Range","bytes 0-600122/600123"));
            expectFailure(f::download);check(f.part.length()==1234 && !f.target.exists(),"bad range modified saved progress");
        });
        test("HTTP error preserves partial",()->{
            Fixture f=new Fixture();f.partial(1234);f.add(503,new byte[0],Map.of());expectFailure(f::download);
            check(f.part.length()==1234 && !f.target.exists(),"server error modified the model");
        });
        test("HTTPS downgrade rejected",()->{
            Fixture f=new Fixture();f.add(302,new byte[0],Map.of("Location","http://cdn.invalid/model"));expectFailure(f::download);
            check(f.requests.size()==1 && !f.target.exists(),"cleartext request was permitted");
        });
        test("cached model does not open a connection",()->{
            Fixture f=new Fixture();Files.write(f.target.toPath(),MODEL);f.download();check(f.requests.isEmpty(),"cached model triggered a download");
        });
        test("cancelled setup does not start a request",()->{
            Fixture f=new Fixture();ModelDownloader.Cancellation cancel=new ModelDownloader.Cancellation();cancel.cancel();
            expectFailure(()->f.downloader.download(f.target,QUIET,cancel));check(f.requests.isEmpty(),"cancelled setup used the network");
        });
        test("oversized response is rejected",()->{
            Fixture f=new Fixture();f.add(200,Arrays.copyOf(MODEL,MODEL.length+1),Map.of());expectFailure(f::download);
            check(!f.target.exists(),"oversized response installed");
        });
        test("oversized stale partial restarts",()->{
            Fixture f=new Fixture();Files.write(f.part.toPath(),new byte[MODEL.length+1]);f.add(200,MODEL,Map.of());f.download();f.installed();
            check(f.requests.get(0).range==null,"oversized partial was resumed");
        });
        System.out.println("Downloader checks passed: "+passed+" / "+passed);
    }

    private interface Checked {void run() throws Exception;}
    private static void test(String name,Checked action) throws Exception {action.run();passed++;System.out.println("PASS "+name);}
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void expectFailure(Checked action) throws Exception {
        try {action.run();} catch(IOException expected) {return;}
        throw new AssertionError("Expected IOException");
    }
    private static String hash(byte[] data) throws Exception {
        StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(data))result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();
    }

    private static final class Fixture {
        final File target,part;
        final Deque<Response> responses=new ArrayDeque<>();
        final List<Response> requests=new ArrayList<>();
        final ModelDownloader downloader;
        final URL source;
        Proxy selectedProxy;
        Fixture() throws Exception {
            File folder=Files.createTempDirectory("gemma-download-test-").toFile();folder.deleteOnExit();
            target=new File(folder,"model.litertlm");part=new File(folder,"model.litertlm.download.part");
            source=new URL(null,"https://model.invalid/model",new URLStreamHandler() {
                protected URLConnection openConnection(URL url,Proxy proxy) {selectedProxy=proxy;return openConnection(url);}
                protected URLConnection openConnection(URL url) {
                    Response response=responses.poll();if(response==null)throw new AssertionError("Unexpected request to "+url);
                    requests.add(response);return response.connection(url);
                }
            });
            downloader=new ModelDownloader(source,MODEL.length,hash(MODEL));
        }
        void add(int code,byte[] bytes,Map<String,String> headers) {responses.add(new Response(code,bytes,headers));}
        void partial(int count) throws IOException {Files.write(part.toPath(),Arrays.copyOf(MODEL,count));}
        void range(int offset) {add(206,Arrays.copyOfRange(MODEL,offset,MODEL.length),Map.of("Content-Range","bytes "+offset+"-"+(MODEL.length-1)+"/"+MODEL.length));}
        void download() throws IOException {downloader.download(target,QUIET,new ModelDownloader.Cancellation());}
        void installed() throws IOException {check(Arrays.equals(Files.readAllBytes(target.toPath()),MODEL),"installed bytes differ");check(!part.exists(),"partial was not atomically promoted");}
    }

    private static final class Response {
        final int code;final byte[] body;final Map<String,String> headers;String range;
        Response(int code,byte[] body,Map<String,String> headers) {this.code=code;this.body=body;this.headers=headers;}
        HttpURLConnection connection(URL url) {return new HttpURLConnection(url) {
            public void connect() {}
            public void disconnect() {}
            public boolean usingProxy() {return false;}
            public int getResponseCode() {range=getRequestProperty("Range");return code;}
            public String getHeaderField(String name) {return headers.get(name);}
            public long getContentLengthLong() {return Long.parseLong(headers.getOrDefault("Content-Length",String.valueOf(body.length)));}
            public InputStream getInputStream() {return new ByteArrayInputStream(body);}
        };}
    }
}
