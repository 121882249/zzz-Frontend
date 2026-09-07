import runpy,sys,json,urllib.request,pathlib
# Load helpers without executing the plain-text probe.
selected = int(sys.argv[1]) if len(sys.argv)>1 else None
sys.argv=['claude-single-key-live.py','library']
ns=runpy.run_path(pathlib.Path(__file__).with_name('claude-single-key-live.py'))
models=ns['models'];post=ns['post'];config=ns['config'];alias=ns['alias']
for g,n,_,_ in (models if selected is None else [models[selected]]):
 user={'role':'user','content':'Call get_test_value, then tell me its result. Do not use any other tool.'}
 tools=[{'name':'get_test_value','description':'Returns test value','input_schema':{'type':'object','properties':{},'required':[]}}]
 events=post(g,n,messages=[user],tools=tools)
 if not isinstance(events,list):continue
 blocks={};arg={}
 for e in events:
  i=e.get('index',0);t=e.get('type')
  if t=='content_block_start':blocks[i]=e['content_block'].copy()
  if t=='content_block_delta':
   d=e['delta'];b=blocks.get(i,{})
   if d['type']=='text_delta':b['text']=b.get('text','')+d['text']
   if d['type']=='thinking_delta':b['thinking']=b.get('thinking','')+d['thinking']
   if d['type']=='signature_delta':b['signature']=b.get('signature','')+d['signature']
   if d['type']=='input_json_delta':arg[i]=arg.get(i,'')+d['partial_json']
 for i,a in arg.items():blocks[i]['input']=json.loads(a or '{}')
 content=[blocks[i] for i in sorted(blocks)]
 calls=[b for b in content if b['type']=='tool_use']
 if not calls:print('TOOL FAIL',n,'no tool call',flush=True);continue
 messages=[user,{'role':'assistant','content':content},{'role':'user','content':[{'type':'tool_result','tool_use_id':c['id'],'content':'17'} for c in calls]}]
 # Default auto tool choice for the continuation.
 body={'model':alias(g,n),'max_tokens':512,'messages':messages,'tools':tools,'tool_choice':{'type':'none'},'stream':False}
 req=urllib.request.Request('http://127.0.0.1:23179/v1/messages',data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+config['token'],'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=180) as f:
   obj=json.loads(f.read());text=' '.join(b.get('text','') for b in obj.get('content',[]));print('TOOL ROUNDTRIP',n,f.status,'text',text[:100],'stop',obj.get('stop_reason'),flush=True)
 except Exception as e:
  print('TOOL FAIL',n,str(e),e.read().decode()[:1000] if hasattr(e,'read') else '',flush=True)
