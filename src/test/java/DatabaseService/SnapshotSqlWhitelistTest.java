package DatabaseService;
import org.junit.Test;
import java.sql.SQLException;
import java.util.List;
import static org.junit.Assert.*;
public class SnapshotSqlWhitelistTest {
 @Test public void auditedPageFunctionsAndGrammarRemainAccepted()throws Exception{
  for(String sql:List.of("SELECT COUNT(*) FROM ALL_NON_INVESTMENT_ASSETS WHERE USER_ID=?", "SELECT ID FROM INCOME_AND_EXPENDITURE_DATA WHERE TYPE NOT IN ('transfer_in','transfer_out') AND EXTRACT(MONTH FROM DATE) IN (?,?) AND (LOWER(NAME) LIKE ? ESCAPE '!' OR LOWER(PLATFORM) LIKE ? ESCAPE '!') ORDER BY CAST(AMOUNT AS DECIMAL(15,2))", "SELECT id,CAST(name AS BINARY) FROM (SELECT ID AS id,NAME AS name FROM ALL_NON_INVESTMENT_ASSETS WHERE USER_ID=? UNION ALL SELECT ID AS id,INVESTMENT_PLATFORM AS name FROM ALL_INVESTMENT_ASSETS WHERE USER_ID=?) P ORDER BY name LIMIT ? OFFSET ?", "SELECT COALESCE(OBSERVED_THROUGH, DATE) FROM ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES"))SnapshotSql.verify(sql);
  SnapshotSql.verify("SELECT STRINGTOUTF8(NAME) FROM ALL_NON_INVESTMENT_ASSETS",false);
 }
 @Test public void quotedQualifiedUnknownAndDialectAmbiguousFunctionsAreRefused(){
  for(String sql:List.of("SELECT `GET_LOCK`('x',1)","SELECT `COUNT`(*) FROM USERS","SELECT \"GET_LOCK\"('x',1)","SELECT schema.GET_LOCK('x',1)","SELECT schema.COUNT(*) FROM USERS","SELECT unknown_udf(1)","SELECT SYS_EXEC('x')","SELECT \"schema\".\"unknown_udf\"(1)","SELECT STRINGTOUTF8(NAME) FROM USERS","SELECT DECIMAL(1)","SELECT LOAD_FILE('/private')","SELECT RELEASE_LOCK('x')"))assertThrows(sql,SQLException.class,()->SnapshotSql.verify(sql));
 }
}
