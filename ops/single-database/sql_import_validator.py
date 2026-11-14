"""Fail-closed mysqldump subset validator. No SQL execution or content diagnostics."""
import collections,gzip,hashlib,re
SCHEMAS=set()
MAX_STATEMENT=64*1024*1024
TOKEN=re.compile(r"(?P<WS>\s+)|(?P<COMMENT>--(?=\s|$)[^\n]*(?:\n|$)|\#[^\n]*(?:\n|$)|/\*[\s\S]*?\*/)|(?P<STRING>'(?:\\.|''|[^'\\])*'|\"(?:\\.|\"\"|[^\"\\])*\")|(?P<ID>`(?:``|[^`])*`)|(?P<HEX>0[xX][0-9A-Fa-f]+)|(?P<NUMBER>(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)|(?P<WORD>[A-Za-z_][A-Za-z0-9_$]*)|(?P<SYMBOL>@@|[(),;.=+@<>!*/%:|&^~-])")
class Refused(Exception):pass
def need(ok,code):
 if not ok:raise Refused(code)
def tokens(text):
 at=0;result=[]
 while at<len(text):
  m=TOKEN.match(text,at);need(m is not None,'LEXICAL_FORM_REFUSED_CODEPOINT_'+str(ord(text[at])));at=m.end();kind=m.lastgroup;v=m.group()
  if kind=='WS':continue
  if kind=='COMMENT':
   if v.startswith('/*!'):
    inner=re.sub(r'^/\*!\d{5,6}\s*','',v)[:-2];need(inner!=v[:-2],'UNVERSIONED_EXECUTABLE_COMMENT');result.extend(tokens(inner))
   continue
  if kind=='STRING':result.append(('S',v))
  elif kind=='ID':need(re.fullmatch(r'`[A-Za-z0-9_]+`',v),'IDENTIFIER_REFUSED');result.append(('I',v[1:-1]))
  elif kind=='HEX':result.append(('H','HEX'))
  elif kind=='NUMBER':result.append(('N',v))
  else:result.append(('P'if kind=='SYMBOL'else'W',v.upper()))
 return result

def canon(ts):return tuple(v if k!='S'else v for k,v in ts)
# Exact harmless session setup/teardown emitted by the current dump; no arbitrary variables or expressions.
SESSION=[
'SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT','SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS','SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION','SET NAMES utf8mb4','SET @OLD_TIME_ZONE=@@TIME_ZONE',"SET TIME_ZONE='+00:00'",'SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0','SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0',"SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO'",'SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0','SET @MYSQLDUMP_TEMP_LOG_BIN=@@SESSION.SQL_LOG_BIN','SET @@SESSION.SQL_LOG_BIN=0','SET @saved_cs_client=@@character_set_client','SET character_set_client=utf8mb4','SET character_set_client=@saved_cs_client','SET @@SESSION.SQL_LOG_BIN=@MYSQLDUMP_TEMP_LOG_BIN','SET TIME_ZONE=@OLD_TIME_ZONE','SET SQL_MODE=@OLD_SQL_MODE','SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS','SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS','SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT','SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS','SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION','SET SQL_NOTES=@OLD_SQL_NOTES']
ALLOWED_SET={canon(tokens(x))for x in SESSION}
class Validator:
 def __init__(self):self.current=None;self.created=set();self.used=set();self.tables=set();self.counts=collections.Counter();self.maxStatementBytes=0
 def table(self,ts,pos):
  need(pos<len(ts)and ts[pos][0]in ('I','W'),'TABLE_IDENTIFIER_REQUIRED');name=ts[pos][1];pos+=1;schema=self.current
  if pos<len(ts)and ts[pos][1]=='.':schema=name;need(pos+1<len(ts)and ts[pos+1][0]in ('I','W'),'TABLE_IDENTIFIER_REQUIRED');name=ts[pos+1][1];pos+=2
  need(schema in SCHEMAS and re.fullmatch('[A-Za-z0-9_]+',name),'TABLE_SCHEMA_REFUSED');return(schema,name),pos
 def statement(self,ts):
  if not ts:return
  words=[v for k,v in ts];op=words[0];need(op in {'SET','CREATE','USE','DROP','LOCK','UNLOCK','ALTER','INSERT'},'STATEMENT_TYPE_REFUSED')
  if op=='SET':need(canon(ts)in ALLOWED_SET,'SESSION_ASSIGNMENT_REFUSED')
  elif op=='USE':need(len(ts)==2 and ts[1][0]in ('I','W')and words[1]in SCHEMAS,'USE_SCHEMA_REFUSED');self.current=words[1];self.used.add(self.current)
  elif op=='CREATE'and words[1:4]==['DATABASE','IF','NOT']:
   need(words[4]=='EXISTS'and ts[5][0]in ('I','W')and words[5]in SCHEMAS,'DATABASE_SCOPE_REFUSED');self.created.add(words[5]);tail=ts[6:]
   # Whitelist exact syntax families; collation/charset names are inert identifiers.
   pos=0
   while pos<len(tail):
    w=[v for _,v in tail[pos:]]
    if w[:3]==['DEFAULT','CHARACTER','SET']:pos+=3;need(pos<len(tail)and tail[pos][0]=='W','DATABASE_CHARSET_REFUSED');pos+=1
    elif w[:1]==['COLLATE']:pos+=1;need(pos<len(tail)and tail[pos][0]=='W','DATABASE_COLLATION_REFUSED');pos+=1
    elif w[:2]==['DEFAULT','ENCRYPTION']:pos+=2;need([v for _,v in tail[pos:pos+2]]in [['=',"'N'"],['=',"'Y'"]],'DATABASE_ENCRYPTION_REFUSED');pos+=2
    else:raise Refused('DATABASE_OPTION_REFUSED')
  elif op=='CREATE':
   need(words[1]=='TABLE','OBJECT_TYPE_NOT_REVIEWED');table,pos=self.table(ts,2);need(words[pos]=='('and('W','INNODB')in ts,'TABLE_DEFINITION_REFUSED');body=ts[pos:];plain=[v for k,v in body if k in ('W','S')and k!='S']
   need(all(x not in plain for x in ['SELECT','DIRECTORY','TABLESPACE','CONNECTION','FEDERATED','PLUGIN','SONAME','OUTFILE','DUMPFILE','LOAD_FILE','SLEEP','BENCHMARK','GET_LOCK','RELEASE_LOCK','SYS_EXEC','SYS_EVAL']),'TABLE_EXTERNAL_FEATURE_REFUSED');engines=[i for i,t in enumerate(ts)if t==('W','ENGINE')];need(len(engines)==1 and words[engines[0]+1:engines[0]+3]==['=','INNODB'],'ENGINE_REFUSED')
   for i,t in enumerate(ts[:-2]):
    if t[0]in ('I','W')and ts[i+1][1]=='.'and ts[i+2][0]in ('I','W'):need(t[1]in SCHEMAS,'CROSS_SCHEMA_REFERENCE_REFUSED')
   self.tables.add(table)
  elif op=='DROP':need(words[1:4]==['TABLE','IF','EXISTS'],'DROP_TYPE_REFUSED');_,pos=self.table(ts,4);need(pos==len(ts),'DROP_TAIL_REFUSED')
  elif op=='LOCK':need(words[1]=='TABLES','LOCK_TYPE_REFUSED');_,pos=self.table(ts,2);need(words[pos:]==['WRITE'],'LOCK_TAIL_REFUSED')
  elif op=='UNLOCK':need(words==['UNLOCK','TABLES'],'UNLOCK_REFUSED')
  elif op=='ALTER':need(words[1]=='TABLE','ALTER_TYPE_REFUSED');table,pos=self.table(ts,2);need(table in self.tables and words[pos:]in [['DISABLE','KEYS'],['ENABLE','KEYS']],'ALTER_TAIL_REFUSED')
  elif op=='INSERT':
   need(words[1]=='INTO','INSERT_TYPE_REFUSED');table,pos=self.table(ts,2);need(table in self.tables,'INSERT_TABLE_UNDEFINED')
   # mysqldump default uses no column list; complete-insert is harmless and supported.
   if words[pos]=='(':
    pos+=1
    while pos<len(ts)and words[pos]!=')':need(ts[pos][0]=='I'or words[pos]==',','INSERT_COLUMNS_REFUSED');pos+=1
    need(pos<len(ts),'INSERT_COLUMNS_UNCLOSED');pos+=1
   need(pos<len(ts)and words[pos]=='VALUES','INSERT_VALUES_REQUIRED');pos+=1;need(pos<len(ts),'INSERT_VALUES_EMPTY')
   need(all(k in ('S','H','N')or v in ('NULL','(',')',',','+','-','_BINARY','_UTF8MB4')for k,v in ts[pos:]),'INSERT_EXPRESSION_REFUSED')
  self.counts[op]+=1
 def text(self,text):
  need(len(text.encode())<=MAX_STATEMENT,'STATEMENT_SIZE_LIMIT');self.maxStatementBytes=max(self.maxStatementBytes,len(text.encode()));part=[]
  for t in tokens(text):
   if t[1]==';'and t[0]!='S':self.statement(part);part=[]
   else:part.append(t)
  need(not part,'UNTERMINATED_STATEMENT')
 def done(self):need(self.created==self.used==SCHEMAS,'EXACT_SCHEMA_SET_REQUIRED');return {'schemaCount':len(self.used),'tableCount':len(self.tables),'statementCounts':dict(self.counts),'maxStatementBytes':self.maxStatementBytes,'routinesEventsTriggersAccepted':False,'dataOrSqlEmitted':False}
def validate_gzip(path,expected_bytes,expected_sha,schemas):
 global SCHEMAS
 need(isinstance(schemas,list) and schemas and len(set(schemas))==len(schemas) and all(isinstance(v,str) and re.fullmatch('[A-Za-z0-9_]+',v) and v not in ('mysql','sys','performance_schema','information_schema') for v in schemas),'INVALID_SCHEMA_SET')
 SCHEMAS=set(schemas)
 v=Validator();total=0;h=hashlib.sha256();pending=[];pendingBytes=0
 with gzip.open(path,'rb')as f:
  while True:
   line=f.readline(MAX_STATEMENT+1)
   if not line:break
   need(len(line)<=MAX_STATEMENT,'LINE_SIZE_LIMIT')
   total+=len(line);need(total<=expected_bytes,'EXPANSION_LIMIT');h.update(line);text=line.decode('utf-8');pending.append(text);pendingBytes+=len(line);need(pendingBytes<=MAX_STATEMENT,'STATEMENT_SIZE_LIMIT')
   # Complete dump statements terminate at line end; lexical tokenizer verifies every delimiter/quote.
   if text.rstrip().endswith(';'):
    v.text(''.join(pending));pending=[];pendingBytes=0
   elif text.lstrip().startswith(('-- ','#'))and len(pending)==1:pending=[];pendingBytes=0
 need(total==expected_bytes and h.hexdigest()==expected_sha,'EXPANDED_HASH_MISMATCH');need(not tokens(''.join(pending)),'DUMP_TAIL_REFUSED');result=v.done();result.update(expandedBytes=total,expandedSha256=h.hexdigest(),validationPassed=True);return result
