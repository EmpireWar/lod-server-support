package dev.vox.lss.jdbc;

import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Defined from opaque bytes by the private loader; never placed on the host classpath. */
public final class PrivateSqliteBridge {
    public static String initialize() throws Exception {
        try {
            Class.forName("org.sqlite.JDBC", true, PrivateSqliteBridge.class.getClassLoader());
            try (var c = dataSource("jdbc:sqlite::memory:").getConnection();
                 var st = c.createStatement(); var rs = st.executeQuery("SELECT sqlite_version()")) {
                if (!rs.next()) throw new SQLException("SQLite bootstrap query returned no row");
                return rs.getString(1);
            }
        } finally {
            // DriverManager is caller-sensitive: only this child can see/remove its driver.
            for (var drivers = DriverManager.getDrivers(); drivers.hasMoreElements();) {
                var driver = drivers.nextElement();
                if (driver.getClass().getClassLoader() == PrivateSqliteBridge.class.getClassLoader()) {
                    DriverManager.deregisterDriver(driver);
                }
            }
        }
    }

    public static DataSource dataSource(String url) {
        var source = new org.sqlite.SQLiteDataSource();
        source.setUrl(url);
        return source;
    }

    /** Caller-sensitive registration evidence used by the forked native probe. */
    public static int registeredDrivers() {
        int count = 0;
        for (var drivers = DriverManager.getDrivers(); drivers.hasMoreElements();) {
            if (drivers.nextElement().getClass().getClassLoader() == PrivateSqliteBridge.class.getClassLoader()) count++;
        }
        return count;
    }
}
