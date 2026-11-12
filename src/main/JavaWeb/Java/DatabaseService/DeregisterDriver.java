package DatabaseService;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

class DeregisterDriver {
    void deregisterDriver() {
        ClassLoader application = getClass().getClassLoader();
        java.util.Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == application) {
                try { DriverManager.deregisterDriver(driver); }
                catch (SQLException exception) { System.err.println("A database driver could not be deregistered."); }
            }
        }
    }
}
