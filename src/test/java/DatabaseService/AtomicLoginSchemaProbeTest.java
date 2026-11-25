package DatabaseService;
import java.sql.*;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class AtomicLoginSchemaProbeTest {
 @Test public void combinedProbeStillRejectsMissingColumnsAndDeniedTables()throws Exception {
  String url="jdbc:h2:mem:"+UUID.randomUUID();
  try(Connection admin=DriverManager.getConnection(url,"sa","");Statement s=admin.createStatement()){
   s.execute("CREATE TABLE LOGIN_LIMITS(CLIENT_HASH VARCHAR,WINDOW_START_MS BIGINT,ATTEMPTS INT)");
   s.execute("CREATE TABLE LOGIN_STATUS_LIMITS(CLIENT_HASH VARCHAR,WINDOW_START_MS BIGINT,ATTEMPTS INT)");
   s.execute("CREATE TABLE LOGIN_ATTEMPTS(OPERATION_ID VARCHAR,CLIENT_HASH VARCHAR,EMAIL_HASH VARCHAR,REQUEST_HASH VARCHAR,HTTP_STATUS INT,USER_ID INT,USER_GENERATION VARCHAR,CREATED_AT_MS BIGINT)");
   s.execute("CREATE USER QA PASSWORD 'qa'");s.execute("GRANT SELECT ON LOGIN_LIMITS TO QA");s.execute("GRANT SELECT ON LOGIN_STATUS_LIMITS TO QA");
   try(Connection restricted=DriverManager.getConnection(url,"QA","qa")){try{AtomicLoginAttempts.verifySchema(restricted);fail("Denied table must fail readiness");}catch(SQLException expected){}}
   s.execute("GRANT SELECT ON LOGIN_ATTEMPTS TO QA");
   try(Connection restricted=DriverManager.getConnection(url,"QA","qa")){AtomicLoginAttempts.verifySchema(restricted);}
   s.execute("ALTER TABLE LOGIN_ATTEMPTS DROP COLUMN CREATED_AT_MS");
   try(Connection restricted=DriverManager.getConnection(url,"QA","qa")){try{AtomicLoginAttempts.verifySchema(restricted);fail("Missing column must fail readiness");}catch(SQLException expected){}}
  }
 }
}
