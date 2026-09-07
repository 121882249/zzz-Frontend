import json,pathlib,hashlib,uuid,os,urllib.request,urllib.error,time,sys
path=pathlib.Path('/tmp/tokenpro-claude-one-key.json')
key=json.loads(path.read_text())
models=[(16,'gpt-5.5','openai','GPT'),(62,'grok-4.6','grok','Grok'),(60,'claude-sonnet-5','anthropic','Claude'),(59,'gemini-3.1-pro-high','gemini','Gemini')]
def alias(gid,name):return 'claude-tokenpro-'+hashlib.sha256(f'{gid}::{name}'.encode()).hexdigest()[:24]
config={**key,'port':23179,'token':str(uuid.uuid4()),'routes':[{'model':{'name':n,'platform':p,'groupID':g,'groups':[label]}} for g,n,p,label in models]}
cp=pathlib.Path('/tmp/tokenpro-claude-bridge-test.json')
if len(sys.argv)>1 and sys.argv[1]=='setup':
 fd=os.open(cp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
 with os.fdopen(fd,'w') as f:json.dump(config,f)
 print('isolated bridge config ready');sys.exit()
config=json.loads(cp.read_text())
def post(gid,name,stream=True,messages=None,tools=None):
 body={'model':alias(gid,name),'max_tokens':512,'messages':messages or [{'role':'user','content':'Reply exactly OK.'}],'stream':stream}
 if tools:body.update(tools=tools,tool_choice={'type':'tool','name':tools[0]['name']})
 req=urllib.request.Request('http://127.0.0.1:23179/v1/messages',data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+config['token'],'Content-Type':'application/json'})
 start=time.monotonic()
 try:
  with urllib.request.urlopen(req,timeout=180) as f:
   if not stream:
    obj=json.loads(f.read());print('NONSTREAM',name,f.status,'text',[(b.get('type'),b.get('text','')[:80]) for b in obj.get('content',[])],'usage',obj.get('usage'),flush=True);return obj
   events=[]
   for line in f:
    if line.startswith(b'data:'):
     ev=json.loads(line[5:]);events.append(ev)
   kinds=[e.get('type') for e in events];text=''.join(e.get('delta',{}).get('text','') for e in events)
   errors=[e for e in events if e.get('type')=='error']
   usages=[e.get('usage',e.get('message',{}).get('usage')) for e in events if e.get('type') in ['message_start','message_delta']]
   print('STREAM',name,'seconds',round(time.monotonic()-start,1),'events',len(events),'stop',kinds[-1:] ,'text',text[:80],'usage',usages,'errors',errors,flush=True)
   return events
 except urllib.error.HTTPError as e:print('HTTP',name,e.code,e.read().decode()[:1200],flush=True)
 except Exception as e:print('FAIL',name,type(e).__name__,str(e),flush=True)
if len(sys.argv)>1 and sys.argv[1]=='one':
 idx=int(sys.argv[2]);g,n,_,_=models[idx];post(g,n);sys.exit()
if len(sys.argv)<2 or sys.argv[1]!='library':
 for g,n,_,_ in models:post(g,n)
