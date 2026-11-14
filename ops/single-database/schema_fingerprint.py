"""Narrow, byte-preserving schema fingerprint for canonical mysqldump output.

Only an explicit utf8mb4_unicode_ci collation on a top-level character column
can receive its implied CHARACTER SET utf8mb4 spelling. All other bytes remain.
"""
import hashlib,re
HEADER=re.compile(rb'^CREATE TABLE `(?:[^`]|``)+` \(\r?\n$')
COLUMN=re.compile(rb'^(  `(?:[^`]|``)+` (?:char\([0-9]+\)|varchar\([0-9]+\)|(?:tiny|medium|long)?text)) COLLATE utf8mb4_unicode_ci(?= |,|\r?$)',re.I)
SQL_MODE=b"/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;"
SQL_MODE_END=b'/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;'
LIMIT=16*1024*1024

def validate_schema_binding(manifest):
 algorithm=manifest.get('sourceSchemaDigestAlgorithm');fingerprint=manifest.get('sourceSchemaSha256','')
 if not isinstance(fingerprint,str) or not re.fullmatch(r'[0-9a-f]{64}',fingerprint):raise ValueError('schema fingerprint required')
 if algorithm=='mysql-schema-dump-sha256-v1':return {'schemaAlgorithm':algorithm,'expectedSchemaFingerprint':fingerprint}
 if algorithm!='mysql-schema-dump-charset-semantic-v2':raise ValueError('unsupported schema algorithm')
 raw=manifest.get('sourceRawSchemaSha256','');column=manifest.get('sourceColumnMetadataSha256','');count=manifest.get('sourceColumnCount')
 if not isinstance(raw,str) or not re.fullmatch(r'[0-9a-f]{64}',raw) or not isinstance(column,str) or not re.fullmatch(r'[0-9a-f]{64}',column) or type(count) is not int or count<=0 or manifest.get('sourceColumnMetadataAlgorithm')!='mysql-information-schema-columns-sha256-v1':raise ValueError('column metadata proof required')
 version=manifest.get('sourceMysqlVersion','')
 if not isinstance(version,str) or not re.search(r'(?<![0-9])26\.10\.0(?![0-9])',version):raise ValueError('MySQL version binding required')
 return {'schemaAlgorithm':algorithm,'expectedSchemaFingerprint':fingerprint,'sourceRawSchemaFingerprint':raw,'expectedColumnAlgorithm':manifest['sourceColumnMetadataAlgorithm'],'expectedColumnFingerprint':column,'expectedColumnCount':count}

def schema_hashes(stream):
 raw=hashlib.sha256();normalized=hashlib.sha256();state='code';depth=0;inside=False;seen_mode=False;ended_mode=False;tables=0;edits=0
 while True:
  line=stream.readline(LIMIT+1)
  if not line:break
  if len(line)>LIMIT:raise ValueError('schema line too long')
  raw.update(line);changed=line
  stripped=line.rstrip(b'\r\n')
  if b'SQL_MODE' in line.upper():
   if not inside and state=='code' and stripped==SQL_MODE and not seen_mode and not ended_mode:seen_mode=True
   elif not inside and state=='code' and stripped==SQL_MODE_END and seen_mode and not ended_mode:ended_mode=True
   else:raise ValueError('unexpected SQL mode text')
  if not inside and state=='code' and HEADER.fullmatch(line):
   if not seen_mode or ended_mode:raise ValueError('unrecognized mysqldump SQL mode')
   inside=True;depth=0;tables+=1
  elif inside and state=='code' and depth==1:
   match=COLUMN.match(line)
   if match:
    i=match.end(1);changed=line[:i]+b' CHARACTER SET utf8mb4'+line[i:];edits+=1
  normalized.update(changed)
  i=0
  while i<len(line):
   c=line[i];n=line[i+1] if i+1<len(line) else None
   if state=='block':
    if c==42 and n==47:state='code';i+=2;continue
   elif state in ('single','double','backtick'):
    delim={'single':39,'double':34,'backtick':96}[state]
    if c==92 and state!='backtick':i+=2;continue
    if c==delim:
     if n==delim:i+=2;continue
     state='code'
   else:
    if c==47 and n==42:state='block';i+=2;continue
    if c==35 or (c==45 and n==45 and (i+2==len(line) or line[i+2] in b' \t\r\n')):break
    if c in (39,34,96):state={39:'single',34:'double',96:'backtick'}[c]
    elif inside and c==40:depth+=1
    elif inside and c==41:
     depth-=1
     if depth<0:raise ValueError('unbalanced CREATE TABLE')
     if depth==0:inside=False
   i+=1
 if inside or state in ('single','double','backtick','block') or not seen_mode or not ended_mode:raise ValueError('incomplete canonical schema dump')
 return {'rawSha256':raw.hexdigest(),'schemaSha256':normalized.hexdigest(),'normalizedColumns':edits,'tableCount':tables}


"""Canonical INFORMATION_SCHEMA column digest; no business-row output.
Target installation is MySQL 26.10; native runtime validation is required.

Both source and isolated callers MUST use the same reviewed source, independently
verified executable/configuration/identity, complete metadata privileges, and a
frozen schema window. `allowed_schemas` must come from the separately reviewed
backup inventory, not from the SQL result. A digest is not a restore test.
Official definitions: https://dev.mysql.com/doc/refman/8.4/en/information-schema-columns-table.html
https://dev.mysql.com/doc/refman/8.4/en/information-schema-columns-extensions-table.html
COLUMN_DEFAULT cannot distinguish omitted DEFAULT from explicit DEFAULT NULL;
retain independent DDL evidence for that and for constraints/indexes/triggers.
"""
import hashlib,json,os,pathlib,re,stat,subprocess
ALGORITHM='mysql-information-schema-columns-sha256-v1'
FIELDS=('schema','table','column','ordinal','default','nullable','dataType','charLength','octetLength','numericPrecision','numericScale','datetimePrecision','charset','collation','columnType','columnKey','extra','comment','generationExpression','srsId','engineAttribute','secondaryEngineAttribute')
NUMERIC={'ordinal','charLength','octetLength','numericPrecision','numericScale','datetimePrecision','srsId'}
STRING=set(FIELDS)-NUMERIC-{'engineAttribute','secondaryEngineAttribute'}
class Refused(Exception):pass

def _schemas(schemas,allowed_schemas):
 if not isinstance(schemas,(list,tuple)) or not isinstance(allowed_schemas,(set,frozenset,list,tuple)) or not schemas:raise Refused('Explicit schema whitelist required')
 wanted=list(schemas);allowed=set(allowed_schemas)
 if len(wanted)>128 or len(wanted)!=len(set(wanted)) or not set(wanted)<=allowed:raise Refused('Schema selection not allowed')
 if any(not isinstance(n,str) or not re.fullmatch(r'[A-Za-z0-9_]{1,64}',n) or n.lower() in ('mysql','sys','information_schema','performance_schema') for n in allowed):raise Refused('Unsafe schema whitelist')
 return sorted(wanted,key=lambda n:n.encode('utf-8'))

def build_query(schemas,allowed_schemas):
 names=_schemas(schemas,allowed_schemas);where=','.join("'"+n+"'" for n in names)
 aliases={'schema':'TABLE_SCHEMA','table':'TABLE_NAME','column':'COLUMN_NAME','ordinal':'ORDINAL_POSITION','default':'COLUMN_DEFAULT','nullable':'IS_NULLABLE','dataType':'DATA_TYPE','charLength':'CHARACTER_MAXIMUM_LENGTH','octetLength':'CHARACTER_OCTET_LENGTH','numericPrecision':'NUMERIC_PRECISION','numericScale':'NUMERIC_SCALE','datetimePrecision':'DATETIME_PRECISION','charset':'CHARACTER_SET_NAME','collation':'COLLATION_NAME','columnType':'COLUMN_TYPE','columnKey':'COLUMN_KEY','extra':'EXTRA','comment':'COLUMN_COMMENT','generationExpression':'GENERATION_EXPRESSION','srsId':'SRS_ID'}
 entries=["'record','column'"]+["'"+k+"',c."+v for k,v in aliases.items()]+["'engineAttribute',e.ENGINE_ATTRIBUTE","'secondaryEngineAttribute',e.SECONDARY_ENGINE_ATTRIBUTE"]
 # Output each metadata row as JSON; no GROUP_CONCAT, no table rows and no DDL.
 return ("SET SESSION show_gipk_in_create_table_and_information_schema=ON; "
  "SELECT JSON_OBJECT('record','setting','gipk',@@SESSION.show_gipk_in_create_table_and_information_schema); "
  "SELECT JSON_OBJECT('record','schema','schema',SCHEMA_NAME) FROM information_schema.SCHEMATA WHERE BINARY SCHEMA_NAME IN ("+where+") ORDER BY BINARY SCHEMA_NAME; "
  "SELECT JSON_OBJECT('record','table','schema',TABLE_SCHEMA,'table',TABLE_NAME,'tableType',TABLE_TYPE) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA IN ("+where+") ORDER BY BINARY TABLE_SCHEMA,BINARY TABLE_NAME; "
  "SELECT JSON_OBJECT("+','.join(entries)+") FROM information_schema.COLUMNS c LEFT JOIN information_schema.COLUMNS_EXTENSIONS e ON BINARY e.TABLE_CATALOG=BINARY c.TABLE_CATALOG AND BINARY e.TABLE_SCHEMA=BINARY c.TABLE_SCHEMA AND BINARY e.TABLE_NAME=BINARY c.TABLE_NAME AND BINARY e.COLUMN_NAME=BINARY c.COLUMN_NAME WHERE BINARY c.TABLE_SCHEMA IN ("+where+") ORDER BY BINARY c.TABLE_SCHEMA,BINARY c.TABLE_NAME,c.ORDINAL_POSITION;\n")

def _canonical(row):return json.dumps(row,sort_keys=True,ensure_ascii=True,separators=(',',':'),allow_nan=False).encode('ascii')
def digest_rows(raw,schemas,allowed_schemas,expected_objects=None):
 names=_schemas(schemas,allowed_schemas)
 if not isinstance(raw,bytes) or len(raw)>64*1024*1024:raise Refused('Metadata output size invalid')
 try:rows=[json.loads(line) for line in raw.splitlines()]
 except (UnicodeError,ValueError):raise Refused('Malformed metadata JSON') from None
 if not rows or rows[0]!={'record':'setting','gipk':1}:raise Refused('Invisible generated primary key metadata unavailable')
 schema_rows=[r for r in rows[1:] if isinstance(r,dict) and r.get('record')=='schema'];table_rows=[r for r in rows[1:] if isinstance(r,dict) and r.get('record')=='table'];column_rows=[r for r in rows[1:] if isinstance(r,dict) and r.get('record')=='column']
 if len(rows)!=1+len(schema_rows)+len(table_rows)+len(column_rows) or schema_rows!=[{'record':'schema','schema':n} for n in names]:raise Refused('Incomplete or unexpected schema metadata')
 objects=[]
 for t in table_rows:
  if set(t)!={'record','schema','table','tableType'} or t['schema'] not in names or not isinstance(t['table'],str) or not t['table'] or t['tableType'] not in ('BASE TABLE','VIEW'):raise Refused('Invalid table metadata')
  objects.append((t['schema'],t['table'],t['tableType']))
 if len(objects)!=len(set(objects)) or objects!=sorted(objects,key=lambda x:(x[0].encode(),x[1].encode())):raise Refused('Duplicate or unordered tables')
 if expected_objects is not None and set(objects)!=set(expected_objects):raise Refused('Object metadata incomplete')
 seen=set();positions={};previous=None;h=hashlib.sha256();h.update(b'{"algorithm":'+_canonical(ALGORITHM)+b',"schemas":'+_canonical(names)+b',"objects":'+_canonical(objects)+b',"columns":[')
 for index,row in enumerate(column_rows):
  if set(row)!=set(FIELDS)|{'record'}:raise Refused('Missing or unexpected column attributes')
  if row['schema'] not in names or not isinstance(row['table'],str) or not row['table'] or not isinstance(row['column'],str) or not row['column']:raise Refused('Invalid column identity')
  if type(row['ordinal']) is not int or row['ordinal']<1 or row['nullable'] not in ('YES','NO'):raise Refused('Invalid column position or nullability')
  if any(row[k] is not None and type(row[k]) is not int for k in NUMERIC):raise Refused('Invalid numeric metadata')
  if any(row[k] is not None and not isinstance(row[k],str) for k in STRING):raise Refused('Invalid string metadata')
  if row['dataType'] is None or row['columnType'] is None:raise Refused('Missing column type')
  for k in ('engineAttribute','secondaryEngineAttribute'):
   if row[k] is not None and not isinstance(row[k],(dict,list,str,int,float,bool)):raise Refused('Invalid engine attribute')
  key=(row['schema'],row['table'],row['column']);order=(row['schema'].encode(),row['table'].encode(),row['ordinal'])
  if key in seen or (previous is not None and order<=previous):raise Refused('Duplicate or unordered column metadata')
  prior=positions.get((row['schema'],row['table']),0)
  if row['ordinal']!=prior+1:raise Refused('Missing column ordinal')
  seen.add(key);positions[(row['schema'],row['table'])]=row['ordinal'];previous=order
  if index:h.update(b',')
  h.update(_canonical({k:row[k] for k in FIELDS}))
 if {(x[0],x[1]) for x in objects}!=set(positions):raise Refused('Table columns missing')
 h.update(b']}');return {'algorithm':ALGORITHM,'sha256':h.hexdigest(),'columnCount':len(column_rows)}

def schema_column_digests(mysql_binary,defaults_file,schemas,*,allowed_schemas,expected_objects=None,timeout=60,runner=None):
 """Return one aggregate digest, not metadata; caller must pin host and backup window."""
 binary=pathlib.Path(mysql_binary);config=pathlib.Path(defaults_file)
 if not binary.is_absolute() or not binary.is_file() or not os.access(binary,os.X_OK):raise Refused('MySQL executable unavailable')
 try:s=config.lstat()
 except OSError:raise Refused('Protected defaults file unavailable') from None
 if not config.is_absolute() or config.is_symlink() or config.resolve()!=config or not stat.S_ISREG(s.st_mode) or s.st_uid!=os.geteuid() or stat.S_IMODE(s.st_mode)!=0o600 or s.st_nlink!=1:raise Refused('Protected defaults file required')
 if type(timeout) not in (int,float) or not 1<=timeout<=300:raise Refused('Invalid metadata deadline')
 query=build_query(schemas,allowed_schemas);run=runner or subprocess.run
 try:result=run([str(binary),'--defaults-file='+str(config),'--no-login-paths','--default-character-set=utf8mb4','--batch','--raw','--skip-column-names'],input=query.encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=timeout,check=False)
 except (OSError,subprocess.SubprocessError):raise Refused('Metadata query unavailable') from None
 if result.returncode!=0:raise Refused('Metadata query refused or incomplete')
 return digest_rows(result.stdout,schemas,allowed_schemas,expected_objects)
