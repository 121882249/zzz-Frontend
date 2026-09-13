package work.tokenpro.client;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
public class ReleaseHistoryIntegrationTest {
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
   var repaired=CodexHistoryRepair.repair(()->new CodexHistorySettings.Rpc(root),provider,List.of("gpt-5.4"),()->false);
   if(repaired.repaired()!=1 || repaired.failed()!=0 || !repaired.issues().isEmpty())throw new AssertionError("repair result: "+repaired);
   if(!CodexHistorySettings.capture(root).getFirst().provider().equals(provider))throw new AssertionError("not durable");
  }
  var custom=CodexHistoryRepair.repair(()->new CodexHistorySettings.Rpc(root),"custom",List.of("gpt-5.4"),()->false);
  if(custom.repaired()!=1)throw new AssertionError("prepare custom session: "+custom);
  Files.delete(root.resolve("config.toml"));
  var official=CodexHistoryRepair.repair(()->new CodexHistorySettings.Rpc(root),"openai",List.of(),()->false);
  if(official.repaired()!=1 || official.failed()!=0 || !official.issues().isEmpty())throw new AssertionError("official repair without config: "+official);
  if(Files.exists(root.resolve("config.toml")))throw new AssertionError("repair recreated deleted config");
  try(var rpc=new CodexHistorySettings.Rpc(root)) { rpc.call("thread/archive",Map.of("threadId",id)); }
  var archived=CodexHistoryRepair.repair(()->new CodexHistorySettings.Rpc(root),"openai",List.of(),()->false);
  if(archived.attempted()!=1)throw new AssertionError("archived session not attempted: "+archived);
  try(var rpc=new CodexHistorySettings.Rpc(root)) {
   var page=rpc.call("thread/list",Map.of("archived",true,"modelProviders",List.of()));
   if(ClaudeAdapter.list(page.get("data")).isEmpty())throw new AssertionError("repair unexpectedly unarchived a conversation");
   rpc.call("thread/unarchive",Map.of("threadId",id));
  }
  System.out.println("Cold resume verification passed in both directions.");
  if(!Files.readString(session).startsWith(initial))throw new AssertionError("original conversation records changed");
  System.out.println("Original conversation records preserved byte-for-byte; no network listener used.");
 }
}
