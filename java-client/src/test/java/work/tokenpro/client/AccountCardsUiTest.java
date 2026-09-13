package work.tokenpro.client;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.List;
import javax.imageio.ImageIO;
/** Desktop-only regression harness. Uses an isolated store and synthetic account data. */
public class AccountCardsUiTest {
 static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(o,args);}
 static JButton button(Container c,String prefix){for(Component x:c.getComponents()){if(x instanceof JButton b && b.getText().startsWith(prefix))return b;if(x instanceof Container k){JButton b=button(k,prefix);if(b!=null)return b;}}return null;}
 static boolean label(Container c,String text){for(Component x:c.getComponents()){if(x instanceof JLabel l && l.getText()!=null && l.getText().contains(text))return true;if(x instanceof Container k && label(k,text))return true;}return false;}
 static void layout(Container c){c.doLayout();for(Component x:c.getComponents())if(x instanceof Container k)layout(k);}
 static void capture(TokenProFrame f,String path,int w)throws Exception{f.setSize(w,820);f.validate();layout(f);BufferedImage b=new BufferedImage(w,820,2);Graphics2D g=b.createGraphics();f.getContentPane().printAll(g);g.dispose();ImageIO.write(b,"png",Path.of(path).toFile());}
 public static void main(String[] args)throws Exception{
 if(args.length!=1)throw new IllegalArgumentException("Pass a screenshot output directory");
 Files.createDirectories(Path.of(args[0]));
 UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());UIManager.put("Label.foreground",new Color(242,245,255));
 SwingUtilities.invokeAndWait(()->{TokenProFrame f=null;try{
 f=new TokenProFrame(new SecureStore(Files.createTempDirectory("tokenpro-design-")),false);
 ((CardLayout)get(f,"views")).show((Container)get(f,"viewHost"),"dashboard");
 Method official=TokenProFrame.class.getDeclaredMethod("officialStatus",String.class);official.setAccessible(true);
 if(!official.invoke(null,"Codex").equals("当前：OpenAI 官方配置") || !official.invoke(null,"Claude").equals("当前：Anthropic 官方配置"))
   throw new AssertionError("official provider labels are inconsistent");
 JButton stateLaunch=new JButton(),stateMenu=new JButton();JLabel stateLabel=new JLabel();
 TokenProFrame.applyCliActionState(stateLaunch,stateMenu,stateLabel,"Claude",true,true,0,false);
 if(!stateLabel.getText().equals("当前：Anthropic 官方配置"))throw new AssertionError("restored CLI state does not show its official provider");
 TokenProFrame.AmountExplanationButton amountExplanation=new TokenProFrame.AmountExplanationButton();
 if(amountExplanation.isFocusPainted())throw new AssertionError("amount explanation retains a focus outline after release");
 for(String n:List.of("codexClientInstallLabel","claudeClientInstallLabel","codexCliInstallLabel","claudeCliInstallLabel")){JLabel l=(JLabel)get(f,n);l.setText("已安装");l.setForeground(new Color(97,222,165));}
 for(String n:List.of("homeCodexStatus","homeClaudeStatus","homeCodexCliStatus","homeClaudeCliStatus"))((JLabel)get(f,n)).setText("当前：TokenPro·已选 4 款模型");
 for(String n:List.of("homeCodexSupport","homeClaudeSupport","homeCodexCliSupport","homeClaudeCliSupport"))call(get(f,n),"setCount",new Class[]{long.class},n.contains("Codex")?20L:17L);
 for(String n:List.of("codexLaunch","claudeLaunch","codexCliLaunch","claudeCliLaunch")){JButton b=(JButton)get(f,n);b.setEnabled(true);b.setText("连接");}
 for(String n:List.of("codexModelMenuButton","claudeModelMenuButton","codexCliModelMenuButton","claudeCliModelMenuButton"))((JButton)get(f,n)).setEnabled(true);
 ((JLabel)get(f,"headerBalance")).setText("$20.01");((JLabel)get(f,"headerUser")).setText("演示账户");
 Class<?> item=Class.forName("work.tokenpro.client.TokenProFrame$SubscriptionItem");Constructor<?> c=item.getDeclaredConstructors()[0];c.setAccessible(true);
 call(f,"showSubscriptions",new Class[]{List.class},List.of(c.newInstance("Pro专业额度卡",200d,"2026-10-13T04:01:00Z"),c.newInstance("标准额度卡",50d,"2026-10-20T04:01:00Z")));
 JButton update=(JButton)get(f,"updateButton");call(f,"setUpdateButtonState",new Class[]{String.class,String.class},"latest","已是最新 v"+Main.VERSION);
 f.addNotify();
 ((JLabel)get(f,"homeCodexStatus")).setText("当前：OpenAI 官方配置");
 ((JLabel)get(f,"homeCodexCliStatus")).setText("当前：OpenAI 官方配置");
 ((JLabel)get(f,"homeClaudeStatus")).setText("当前：Anthropic 官方配置");
 ((JLabel)get(f,"homeClaudeCliStatus")).setText("当前：Anthropic 官方配置");
 capture(f,args[0]+"/preview-official-statuses.png",1280);
 for(String n:List.of("homeCodexStatus","homeClaudeStatus","homeCodexCliStatus","homeClaudeCliStatus"))((JLabel)get(f,n)).setText("当前：TokenPro·已选 4 款模型");
 capture(f,args[0]+"/preview-home.png",1280);
 JButton accountAction=(JButton)get(f,"headerAccountButton");
 for(String accountName:List.of("121882249", "121882249@example.com", "long.account.name.for.layout@example.com")){
 ((JLabel)get(f,"headerUser")).setText(accountName);
 for(int width:new int[]{1280,1080}){
 capture(f,args[0]+"/preview-dynamic-user-"+width+".png",width);
 int needed=accountAction.getFontMetrics(accountAction.getFont()).stringWidth(accountName)+accountAction.getIcon().getIconWidth()+accountAction.getIconTextGap()+24;
 if(accountAction.getWidth()<needed)throw new AssertionError("username clipped: "+accountName);
 Rectangle accountBounds=SwingUtilities.convertRectangle(accountAction.getParent(),accountAction.getBounds(),f.getContentPane());
 if(!new Rectangle(0,0,width,820).contains(accountBounds))throw new AssertionError("username outside viewport");
 }
 }
 ((JLabel)get(f,"headerUser")).setText("演示账户");capture(f,args[0]+"/preview-home.png",1280);
 Container slot=(Container)get(f,"subscriptionSlot");Rectangle slotBounds=slot.getBounds();
 JButton picker=button(slot,"Pro专业额度卡");if(picker==null)throw new AssertionError("missing picker");picker.doClick();
 JButton option=button((Container)get(f,"activeModelMenuOverlay"),"标准额度卡");if(option==null)throw new AssertionError("missing option");option.doClick();
 capture(f,args[0]+"/preview-switched.png",1280);
 if(button(slot,"标准额度卡")==null || (!label(slot,"$50.00") || !label(slot,"2026-10-20")))throw new AssertionError("subscription details did not switch");
 if(!slotBounds.equals(slot.getBounds()))throw new AssertionError("card moved after switching");
 JButton purchase=(JButton)get(f,"subscriptionPurchaseButton");JButton recharge=button(f.getContentPane(),"充值");
 if(recharge==null || purchase.getClientProperty("tokenpro.webGate")==recharge.getClientProperty("tokenpro.webGate"))throw new AssertionError("purchase controls must have separate gates");
 JButton b=(JButton)get(f,"claudeCliLaunch");Rectangle before=b.getBounds();b.setText("连接中…");b.setEnabled(false);capture(f,args[0]+"/preview-connecting.png",1280);if(!before.equals(b.getBounds()))throw new AssertionError("button moved");
 capture(f,args[0]+"/preview-narrow.png",1080);
 String[] statuses={"homeCodexStatus","homeClaudeStatus","homeCodexCliStatus","homeClaudeCliStatus"};int[] counts={4,10,100,999};
 for(int i=0;i<statuses.length;i++)((JLabel)get(f,statuses[i])).setText("当前：TokenPro·已选 "+counts[i]+" 款模型");
 capture(f,args[0]+"/preview-counts.png",1080);
 for(String n:statuses){JLabel l=(JLabel)get(f,n);if(l.getFontMetrics(l.getFont()).stringWidth(l.getText())>l.getWidth())throw new AssertionError("count clipped: "+l.getText());}
 call(f,"showSubscriptions",new Class[]{List.class},List.of());capture(f,args[0]+"/preview-unsubscribed.png",1280);capture(f,args[0]+"/preview-unsubscribed-narrow.png",1080);
 if(slot.getComponentCount()!=1 || button(slot,"订阅")==null)throw new AssertionError("inactive card missing");
 if(!((JButton)get(f,"refreshSubscriptionButton")).isEnabled())throw new AssertionError("refresh unavailable");
 Rectangle narrowSlotBounds=slot.getBounds();
 int guardedCount=((List<?>)get(f,"guardedWebButtons")).size();
 ((JButton)get(f,"refreshAccountButton")).setEnabled(false);
 call(f,"showSubscriptions",new Class[]{List.class},List.of(c.newInstance("团队旗舰版超长套餐名称 · Claude 与 Codex 全模型年度订阅",99999d,"2027-10-20T04:01:00Z")));
 capture(f,args[0]+"/preview-long-subscription.png",1080);
 if(!narrowSlotBounds.equals(slot.getBounds()))throw new AssertionError("long subscription resized card");
 if(((JButton)get(f,"refreshSubscriptionButton")).isEnabled())throw new AssertionError("rebuild enabled in-flight refresh");
 if(((List<?>)get(f,"guardedWebButtons")).size()!=guardedCount)throw new AssertionError("old purchase buttons leaked");
 for(String n:List.of("refreshSubscriptionButton","subscriptionPurchaseButton")){
 JButton action=(JButton)get(f,n);Rectangle bounds=SwingUtilities.convertRectangle(action.getParent(),action.getBounds(),slot);
 if(!new Rectangle(0,0,slot.getWidth(),slot.getHeight()).contains(bounds))throw new AssertionError("action overflow: "+n);
 }
 call(f,"finishAccountRefresh",new Class[]{String.class},"订阅刷新失败，点击重试");
 for(String n:List.of("refreshAccountButton","refreshSubscriptionButton"))if(!((JButton)get(f,n)).isEnabled())throw new AssertionError("retry button disabled");
 System.out.println("UI regression passed: long subscription, refresh retry, guard cleanup, subscription switching, fixed card bounds, independent purchase gates, inactive card, counts 4/10/100/999 and connection loading bounds.");
 }catch(Exception e){throw new RuntimeException(e);}finally{if(f!=null)f.dispose();}});System.exit(0);
 }
}
