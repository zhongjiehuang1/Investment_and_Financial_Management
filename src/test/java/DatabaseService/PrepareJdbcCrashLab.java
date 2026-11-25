package DatabaseService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;

/** Prepares empty, explicitly named loopback-only schemas for process crash tests. */
public final class PrepareJdbcCrashLab {
    private static void requireLab(String url) {
        if(url==null||!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/finance_validation_[a-z0-9_]+[?].*"))
            throw new IllegalArgumentException("Refusing a non-validation database");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Private test configuration required");
        Properties p=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]))){p.load(r);}
        for(int n:new int[]{1,2}) {
            String url=p.getProperty("node"+n+".url");requireLab(url);
            try(var c=DriverManager.getConnection(url,p.getProperty("node"+n+".user"),p.getProperty("node"+n+".password"));var s=c.createStatement()) {
                String ddl=Files.readString(Path.of("ops/cluster/finance-schema.sql")).replaceAll("(?m)^--.*$","");
                for(String sql:ddl.split(";"))if(!sql.isBlank())s.execute(sql);
                s.execute("CREATE TABLE TEST_BALANCE(ID INT PRIMARY KEY,AMOUNT INT NOT NULL)");
                s.execute("INSERT INTO TEST_BALANCE VALUES(1,0)");
            }
        }
        String journal=p.getProperty("objectStore.jdbc.url");requireLab(journal);
        try(var c=DriverManager.getConnection(journal,p.getProperty("objectStore.jdbc.user"),p.getProperty("objectStore.jdbc.password"));var s=c.createStatement()) {
            String ddl=Files.readString(Path.of("ops/cluster/finance-jdbc-decision-store.sql")).replaceAll("(?m)^--.*$","");
            for(String sql:ddl.split(";"))if(!sql.isBlank())s.execute(sql);
        }
        ClusterStoreProvision.provision(p);
        String identity=Files.readString(Path.of(p.getProperty("objectStore.absolutePath"),"identity")).trim();
        try(var c=DriverManager.getConnection(journal,p.getProperty("objectStore.jdbc.user"),p.getProperty("objectStore.jdbc.password"));var q=c.prepareStatement("INSERT INTO iafm_decision_store_identity(ID,STORE_UUID) VALUES(1,?)")) {
            q.setString(1,identity);q.executeUpdate();
        }
    }
}
