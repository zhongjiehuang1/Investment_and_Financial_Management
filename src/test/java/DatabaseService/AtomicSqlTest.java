package DatabaseService;

import java.sql.SQLException;
import org.junit.Test;
import static org.junit.Assert.*;

public class AtomicSqlTest {
    @Test public void transactionalSqlAndBoundLiteralsAreAccepted()throws Exception{
        for(String sql:new String[]{"SELECT ID FROM USERS WHERE EMAIL_ADDRESS=? FOR UPDATE", "INSERT INTO USERS(ID,EMAIL_ADDRESS) VALUES(?,?)", "UPDATE USERS SET FORENAME='a@b;--/*' WHERE ID=?", "DELETE FROM LOGIN_LIMITS WHERE WINDOW_START_MS<=?", "SELECT `ID` FROM `USERS`"})AtomicSql.verify(sql);
    }
    @Test public void implicitCommitsVersionCommentsAndExternalEffectsAreRejected(){
        for(String sql:new String[]{"SET autocommit=1", "COMMIT", "XA PREPARE 'x'", "CREATE TABLE x(ID INT)", "SELECT 1; COMMIT", "SELECT 1 /*!50000 INTO OUTFILE '/tmp/x'*/", "SELECT 1 -- comment", "SELECT @x:=1", "SELECT GET_LOCK('x',0)", "SELECT LOAD_FILE('/etc/passwd')", "SELECT SLEEP(1)", "SELECT LAST_INSERT_ID(1)", "SELECT 1 INTO DUMPFILE '/tmp/x'", "SELECT 'unterminated"})assertThrows(sql,SQLException.class,()->AtomicSql.verify(sql));
    }
}
