package work.tokenpro.client;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
public class CodexHistorySettingsIntegrationTest {
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("tokenpro-provider-integration-");
  String id=UUID.randomUUID().toString();
  Path session=root.resolve("sessions/2026/09/13/rollout-2026-09-13T01-00-00-"+id+".jsonl");
  Files.createDirectories(session.getParent());
  Files.writeString(root.resolve("config.toml"), "model=\"gpt-5.4\"\nmodel_provider=\"custom\"\n[model_providers.custom]\nname=\"Fixture\"\nbase_url=\"http://127.0.0.1:1/v1\"\nwire_api=\"responses\"\nrequires_openai_auth=false\n");
  String initial=Json.stringify(Map.of("timestamp","2026-09-13T01:00:00Z","type","session_meta","payload",Map.of("id",id,"timestamp","2026-09-13T01:00:00Z","cwd",root.toString(),"originator","codex_cli_rs","cli_version","0.154.0","source","cli","model_provider","openai")))+"\n"
   +Json.stringify(Map.of("timestamp","2026-09-13T01:00:01Z","type","response_item","payload",Map.of("type","message","role","user","content",List.of(Map.of("type","input_text","text","Preserve synthetic message")))))+"\n"
   +Json.stringify(Map.of("timestamp","2026-09-13T01:00:01Z","type","event_msg","payload",Map.of("type","user_message","message","Preserve synthetic message","images",List.of())))+"\n";
  Files.writeString(session,initial);
  var old=CodexHistorySettings.capture(root);
  if(old.size()!=1)throw new AssertionError("expected one synthetic thread: "+old.size());
  for(String provider:List.of("custom","openai")) {
   var change=old.stream().map(s->new CodexHistorySettings.Setting(s.id(),provider,s.model())).toList();
   CodexHistorySettings.apply(root,change);
   if(!CodexHistorySettings.capture(root).getFirst().provider().equals(provider))throw new AssertionError("not durable");
  }
  System.out.println("Cold resume verification passed in both directions.");
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  CountDownLatch received=new CountDownLatch(1);AtomicReference<String> auth=new AtomicReference<>();
  server.createContext("/v1/responses",exchange->{
   auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
   exchange.getRequestBody().readAllBytes();
   byte[] bytes=("event: response.completed\\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_fixture\",\"status\":\"completed\",\"output\":[]}}\\n\\n").replace("\\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","text/event-stream");
   exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();received.countDown();
  });server.start();
  try {
   String config="model=\"gpt-5.4\"\nmodel_provider=\"custom\"\n[model_providers.custom]\nname=\"Fixture\"\nbase_url=\"http://127.0.0.1:"+server.getAddress().getPort()+"/v1\"\nwire_api=\"responses\"\nrequires_openai_auth=false\nexperimental_bearer_token=\"tokenpro-probe\"\n";
   Files.writeString(root.resolve("config.toml"),config);
   var change=old.stream().map(s->new CodexHistorySettings.Setting(s.id(),"custom","gpt-5.4")).toList();
   CodexHistorySettings.apply(root,change);
   try(var rpc=new CodexHistorySettings.Rpc(root)) {
    rpc.call("thread/resume",Map.of("threadId",old.getFirst().id(),"excludeTurns",true));
    rpc.call("turn/start",Map.of("threadId",old.getFirst().id(),"input",List.of(Map.of("type","text","text","Synthetic routing check"))));
    if(!received.await(20,TimeUnit.SECONDS))throw new AssertionError("request missed target bridge");
    if(!"Bearer tokenpro-probe".equals(auth.get()))throw new AssertionError("configured authentication missing");
   }
   System.out.println("Actual request reached the local bridge with configured Bearer authentication.");
  } finally {server.stop(0);}
  if(!Files.readString(session).startsWith(initial))throw new AssertionError("original conversation records changed");
  System.out.println("Original conversation records preserved byte-for-byte.");
 }
}
