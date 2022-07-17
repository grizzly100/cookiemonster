import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Delete specific cookies from the local Chrome Cookie database using a list of
 * pre-defined rules (keep, delete) held in a CSV. CHROME MUST NOT BE RUNNING.
 * <p>
 * This enables a partial reset of cookies, without deleting the ones with important info.
 * <p>
 * Program appends any host keys that are found in the cookie database but not in the action file
 */
public class Main {

    public static void main(String[] args) throws IOException {
        new Main().run();
    }

    final protected List<String> newHostKeys = new LinkedList<>();
    final protected List<String> undecidedHostKeys = new LinkedList<>();
    final protected List<String> keepHostKeys = new LinkedList<>();

    protected void run() throws IOException {

        // Actions (keep, delete) stored in CSV file
        String actionsPath = getActionsAbsolutePath();
        if (!new File(actionsPath).exists()) {
            System.out.println("Cannot locate actions CSV file at " + actionsPath);
            System.exit(-1);
        }
        Map<String, String> actions = getMapFromCSV(actionsPath);
        System.out.println("Read " + actions.size() + " actions from " + actionsPath);

        // Chrome stores cookies in a SQLLite database file
        String cookiesPath = getChromeCookiesAbsolutePath();
        if (!new File(cookiesPath).exists()) {
            System.out.println("Cannot locate cookies SQLLite database file at " + cookiesPath);
            System.exit(-1);
        }

        try (Connection connection = getConnection(cookiesPath)) {

            // Get all distinct host_keys from cookie database
            Statement statement = connection.createStatement();
            statement.setQueryTimeout(30);  // set timeout to 30 sec.
            ResultSet rs = statement.executeQuery("select distinct host_key from cookies");

            while (rs.next()) {
                // Compare the host_key vs the pre-defined action
                String host_key = rs.getString("host_key");
                String action = actions.get(host_key);

                if (action == null) {
                    onNew(host_key);
                } else if ("undecided".equalsIgnoreCase(action)) {
                    onUndecided(host_key);
                } else if ("keep".equalsIgnoreCase(action)) {
                    onKeep(host_key);
                } else if ("delete".equalsIgnoreCase(action)) {
                    onDelete(host_key, connection);
                }
            }
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }

        if (newHostKeys.size() > 0) {
            System.out.println("WARNING: New host_keys found:");
            System.out.println(Arrays.toString(newHostKeys.toArray()));
            // append new keys to the file, pending an action decision
            appendToCSV(actionsPath, newHostKeys);
        }

        if (undecidedHostKeys.size() > 0) {
            System.out.println("WARNING: Undecided host_keys with no action:");
            System.out.println(Arrays.toString(undecidedHostKeys.toArray()));
        }
    }

    protected Connection getConnection(String cookiesPath) throws SQLException {
        String jdbcURL = "jdbc:sqlite:" + cookiesPath;
        System.out.println("Attempting connection to " + jdbcURL);
        return DriverManager.getConnection(jdbcURL);
    }

    // event handlers

    protected void onNew(String host_key) {
        newHostKeys.add(host_key);
    }

    protected void onUndecided(String host_key) {
        undecidedHostKeys.add(host_key);
    }

    protected void onKeep(String host_key) {
        keepHostKeys.add(host_key);
    }

    protected void onDelete(String host_key, Connection connection) throws SQLException {
        if (host_key == null || host_key.length() < 2) {
            throw new IllegalArgumentException("HostKey too short (" + host_key + ")");
        }
        Statement deleteStmt = connection.createStatement();
        String sql = "delete from cookies where host_key = '" + host_key + "'";
        System.out.println("DELETE: " + sql);
        deleteStmt.executeUpdate(sql);
        // "connection.commit();" not needed as "database in auto-commit mode"
    }

    // helpers

    protected String getActionsAbsolutePath() {
        String filename = "Cookies.csv";
        return Paths.get("src", "main", "resources", filename).toAbsolutePath().toString();
    }

    protected String getChromeCookiesAbsolutePath() {
        String user_home = System.getProperty("user.home");
        String profile = "Profile 2"; // need to determine at runtime
        return Paths.get(user_home, "AppData", "Local", "Google", "Chrome", "User Data",
                profile, "Network", "Cookies").toAbsolutePath().toString();
    }

    protected static Map<String, String> getMapFromCSV(final String filePath) throws IOException {
        Function<String[], String> getAction = (s) -> (s.length > 1) ? s[1] : "undecided";
        return Files.lines(Paths.get(filePath))
                .map(line -> line.split(","))
                .collect(Collectors.toMap(line -> line[0], getAction));
    }

    protected static void appendToCSV(String filename, List<String> listHostKeys) throws IOException {
        try (FileWriter fileWriter = new FileWriter(filename, true);
             PrintWriter printWriter = new PrintWriter(fileWriter)) {
            listHostKeys.forEach(host_key -> printWriter.println(host_key + ","));
        }
    }

}
