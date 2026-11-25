package DatabaseService;

class RegisterDriver {

    void registerDriver() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException classNotFoundException) {
            System.out.println("无法加载数据库驱动(不可预测, 需终止程序): " + classNotFoundException.getMessage() + " (DatabaseService" +
                    "/RegisterDriver.java)");
            throw new IllegalStateException("Database driver lifecycle failed.");
        }
    }

}
