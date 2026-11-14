package DatabaseService;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.*;
/** Conservative SELECT grammar: catalogs are selected only by the owned view. */
final class SnapshotSql {
 static void verify(String sql)throws SQLException { verify(sql,true); }
 static void verify(String sql,boolean mysql)throws SQLException{
  AtomicSql.verify(sql);
  // The audited page SQL uses unquoted identifiers and single-quoted literals.
  // Reject ambiguity instead of depending on ANSI_QUOTES or backtick parsing.
  boolean literal=false;
  for(int i=0;i<sql.length();i++){char c=sql.charAt(i);if(literal){if(c=='\\'){i++;continue;}if(c=='\''){if(i+1<sql.length()&&sql.charAt(i+1)=='\''){i++;continue;}literal=false;}}else if(c=='\'')literal=true;else if(c=='"'||c=='`')throw new SQLException("Quoted identifiers are outside audited page SQL");}
  StringBuilder text=new StringBuilder();char quote=0;
  for(int i=0;i<sql.length();i++){char c=sql.charAt(i);if(quote!=0){if(c=='\\'){i++;continue;}if(c==quote){if(i+1<sql.length()&&sql.charAt(i+1)==quote){i++;continue;}quote=0;}else if(quote=='`')text.append(Character.toUpperCase(c));continue;}if(c=='\''||c=='"'||c=='`'){quote=c;text.append(' ');}else text.append(Character.toUpperCase(c));}
  Matcher matcher=Pattern.compile("[A-Z_][A-Z0-9_]*|[(),.]").matcher(text);List<String> tokens=new ArrayList<>();while(matcher.find())tokens.add(matcher.group());
  if(tokens.isEmpty()||!tokens.get(0).equals("SELECT"))throw new SQLException("Snapshot work only permits SELECT");
  Set<String> functions=mysql?Set.of("COUNT","LOWER","EXTRACT","CAST","COALESCE"):Set.of("COUNT","LOWER","EXTRACT","CAST","COALESCE","STRINGTOUTF8");
  Set<String> grouping=Set.of("IN","NOT","AND","OR","WHERE","FROM","EXISTS","ON","SELECT");
  for(int i=0;i+1<tokens.size();i++)if(tokens.get(i+1).equals("(")&&tokens.get(i).matches("[A-Z_][A-Z0-9_]*")){
    String name=tokens.get(i);if(i>0&&tokens.get(i-1).equals("."))throw new SQLException("Qualified functions are prohibited");
    boolean type=name.equals("DECIMAL")&&i>0&&tokens.get(i-1).equals("AS");
    if(!functions.contains(name)&&!grouping.contains(name)&&!type)throw new SQLException("Function is outside audited page SELECT whitelist");
  }
  int depth=0;Set<Integer> from=new HashSet<>();boolean table=false;
  for(int i=0;i<tokens.size();i++){String word=tokens.get(i);
   if(Set.of("UPDATE","LOCK","INTO","FOR","PROCEDURE").contains(word))throw new SQLException("Locking or effectful SELECT refused");
   if(word.equals("(")){depth++;table=false;continue;}if(word.equals(")")){from.remove(depth);depth--;table=false;continue;}
   if(Set.of("FROM","JOIN").contains(word)){from.add(depth);table=true;continue;}
   if(Set.of("WHERE","GROUP","ORDER","HAVING","LIMIT","UNION").contains(word)){from.remove(depth);table=false;continue;}
   if(word.equals(",")&&from.contains(depth)){table=true;continue;}
   if(table){if(i+1<tokens.size()&&(tokens.get(i+1).equals(".")||tokens.get(i+1).equals("(")))throw new SQLException("Qualified tables and table functions cannot escape snapshot views");table=false;}
  }
 }
}
