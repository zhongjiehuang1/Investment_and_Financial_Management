package DatabaseService;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
import java.util.UUID;

/** Explicit first-time provisioning. Existing coordinator logs are never recreated or reset. */
public final class ClusterStoreProvision {
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!"--writers-stopped".equals(args[1]))throw new IllegalArgumentException("Private configuration and --writers-stopped required.");
        Properties properties=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]))){properties.load(reader);}
        provision(properties);System.out.println("Coordinator store identity verified.");
    }
    static void provision(Properties properties)throws Exception {
        Path directory=Path.of(properties.getProperty("objectStore.absolutePath"));
        if(!directory.isAbsolute())throw new IllegalArgumentException("Absolute durable path required.");
        Path marker=directory.resolve("identity");
        try(Connection connection=DriverManager.getConnection(properties.getProperty("node1.url"),properties.getProperty("node1.user"),properties.getProperty("node1.password"))) {
            connection.setAutoCommit(false);
            try(Statement statement=connection.createStatement()) {
                String existing=null;
                try(ResultSet rows=statement.executeQuery("SELECT STORE_UUID FROM CLUSTER_COORDINATOR WHERE ID=1 FOR UPDATE")){if(rows.next())existing=rows.getString(1);}
                if(existing!=null) {
                    if(!Files.isRegularFile(marker)||!existing.equals(Files.readString(marker).trim()))throw new IllegalStateException("Existing coordinator identity is missing or mismatched; restore its durable store.");
                    connection.commit();return;
                }
                try(ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM CLUSTER_OPERATIONS")){rows.next();if(rows.getLong(1)!=0)throw new IllegalStateException("Cannot initialize a coordinator over existing operations.");}
                Files.createDirectories(directory);
                String identity;
                if(Files.exists(marker)) {
                    identity=Files.readString(marker).trim();if(!RequestIdentity.validGeneration(identity))throw new IllegalStateException("Invalid existing marker.");
                } else {
                    try(var files=Files.list(directory)){if(files.findAny().isPresent())throw new IllegalStateException("New coordinator directory must be empty.");}
                    identity=UUID.randomUUID().toString();
                    try(FileChannel file=FileChannel.open(marker,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                        file.write(ByteBuffer.wrap((identity+"\n").getBytes(StandardCharsets.UTF_8)));file.force(true);
                    }
                    try(FileChannel parent=FileChannel.open(directory,StandardOpenOption.READ)){parent.force(true);}
                }
                try(PreparedStatement insert=connection.prepareStatement("INSERT INTO CLUSTER_COORDINATOR(ID,STORE_UUID) VALUES(1,?)")){insert.setString(1,identity);insert.executeUpdate();}
                connection.commit();
            }catch(Exception failure){connection.rollback();throw failure;}
        }
    }
}
