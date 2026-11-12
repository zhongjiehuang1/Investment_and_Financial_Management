package DatabaseService;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/** DML-only transaction-bound SQL. Control/DDL/version comments never reach JDBC. */
final class AtomicSql {
    private AtomicSql() { }
    static void verify(String sql)throws SQLException {
        if(sql==null||sql.isBlank())throw new SQLException("Empty atomic statement");StringBuilder tokens=new StringBuilder();char quote=0;
        for(int i=0;i<sql.length();i++){
            char c=sql.charAt(i);
            if(quote!=0){
                if(c=='\\'){if(i+1<sql.length())i++;continue;}
                if(c==quote){if(i+1<sql.length()&&sql.charAt(i+1)==quote)i++;else quote=0;}continue;
            }
            if(c=='\''||c=='"'||c=='`'){quote=c;tokens.append(' ');continue;}
            if(c==';'||c=='#'||c=='@'||c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'||c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-')
                throw new SQLException("Control, comments and multiple atomic statements are prohibited");
            tokens.append(Character.isLetterOrDigit(c)||c=='_'?Character.toUpperCase(c):' ');
        }
        if(quote!=0)throw new SQLException("Unterminated atomic statement literal");
        String[] words=tokens.toString().trim().split("\\s+");if(words.length==0||!Set.of("SELECT","INSERT","UPDATE","DELETE").contains(words[0]))
            throw new SQLException("Only atomic DML is permitted");
        for(String word:words)if(Set.of("COMMIT","ROLLBACK","SAVEPOINT","AUTOCOMMIT","XA","OUTFILE","DUMPFILE","LOAD_FILE","GET_LOCK","RELEASE_LOCK","SLEEP","BENCHMARK","LAST_INSERT_ID","IS_FREE_LOCK","IS_USED_LOCK","RELEASE_ALL_LOCKS").contains(word))
            throw new SQLException("Transaction-control or external SQL effects are prohibited");
    }
}
