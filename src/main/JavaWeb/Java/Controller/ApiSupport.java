package Controller;

import Model.User;
import DatabaseService.RequestIdentity;
import org.json.JSONArray;
import org.json.JSONObject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Common HTTP boundary; each servlet still owns exactly one operation. */
public final class ApiSupport {
    private ApiSupport() { }

    @FunctionalInterface
    public interface Action {
        JSONObject run(User user, JSONObject input) throws Exception;
    }
    @FunctionalInterface public interface AfterCommit { void run(User user,JSONObject result)throws Exception; }

    public static void serve(HttpServletRequest request, HttpServletResponse response,
                             boolean mutation, Action action) {
        serve(request, response, mutation, false, action, (user,result)->{});
    }
    public static void serve(HttpServletRequest request,HttpServletResponse response,boolean mutation,Action action,AfterCommit afterCommit) {
        serve(request,response,mutation,false,action,afterCommit);
    }

    /** Public reference data may be read by the login page; API routes still require a key. */
    public static void servePublicRead(HttpServletRequest request, HttpServletResponse response, Action action) {
        serve(request, response, false, true, action, (user,result)->{});
    }

    static void serveParsedRead(HttpServletRequest request,HttpServletResponse response,JSONObject input,Action action){
        serve(request,response,false,false,action,(user,result)->{},input);
    }
    private static void serve(HttpServletRequest request,HttpServletResponse response,boolean mutation,boolean publicRead,Action action,AfterCommit afterCommit){
        serve(request,response,mutation,publicRead,action,afterCommit,null);
    }
    private static void serve(HttpServletRequest request, HttpServletResponse response,
                              boolean mutation, boolean publicRead, Action action,AfterCommit afterCommit,JSONObject suppliedInput) {
        prepare(response);
        User requestUser = null;
        try {
            request.setCharacterEncoding("UTF-8");
            User user = publicRead && !request.getServletPath().startsWith("/api/")
                    ? null : authenticatedUser(request);
            requestUser = user;
            if (mutation) {
                if (request.getServletPath().startsWith("/api/"))
                    throw new Failure(405, "API keys are read-only.");
                checkOrigin(request);
            }
            JSONObject input = suppliedInput==null?input(request):suppliedInput;
            if (user != null) {
                String browserGeneration = request.getHeader("X-IAFM-User-Generation");
                if ((mutation || browserGeneration != null) && !user.getGeneration().equalsIgnoreCase(browserGeneration == null ? "" : browserGeneration))
                    throw new Failure(401, "Please sign in again.");
            }
            try (RequestIdentity identity = user == null ? null : requestIdentity(user, mutation, request.getServletPath(), input)) {
                if (publicRead && user != null && !DatabaseService.FinanceCluster.enabled()) RequestIdentity.verifyCurrentUser();
                JSONObject result = DatabaseService.FinanceCluster.enabled()
                        ? DatabaseService.FinanceCluster.get().run(request, user, input, mutation, () -> action.run(user, input))
                        : action.run(user, input);
                if (!result.has("status")) result.put("status", "success");
                if (user != null) result.put("userGeneration", user.getGeneration());
                afterCommit.run(user,result);
                write(response, result);
            }
        } catch (ClusterAvailability.Unavailable unavailable) {
            response.setStatus(503);
            write(response, new JSONObject().put("status", "failure").put("code", "CLUSTER_UNAVAILABLE")
                    .put("message", ClusterAvailability.MESSAGE));
        } catch (RequestIdentity.StaleIdentity stale) {
            response.setStatus(stale.isUser() ? 401 : 409);
            if (stale.isUser() && requestUser != null) invalidateMatchingSession(request, requestUser);
            write(response, new JSONObject().put("status", "failure").put("message", stale.getMessage()));
        } catch (DatabaseService.FinanceDataRevision.Rejected staleSnapshot) {
            response.setStatus(staleSnapshot.httpStatus());
            write(response,new JSONObject().put("status","failure").put("code",staleSnapshot.code()).put("reloadRequired",true)
                    .put("operationId",staleSnapshot.operationId()).put("message",staleSnapshot.getMessage()));
        } catch (Failure failure) {
            response.setStatus(failure.status);
            if (failure.status == 401) response.setHeader("WWW-Authenticate", "Bearer");
            write(response, new JSONObject().put("status", "failure").put("message", failure.getMessage()));
        } catch (Model.Tools.Computing.ExchangeRateUnavailableException unavailable) {
            response.setStatus(503);
            write(response, new JSONObject().put("status", "failure")
                    .put("message", "Exchange rate is unavailable. Same-currency data remains accessible."));
        } catch (IllegalArgumentException | org.json.JSONException | ArithmeticException | java.time.DateTimeException exception) {
            response.setStatus(400);
            write(response, new JSONObject().put("status", "failure")
                    .put("message", "Invalid input: check amounts, dates, filters and pagination."));
        } catch (java.sql.SQLException unavailable) {
            response.setStatus(503);
            write(response,new JSONObject().put("status","failure").put("code","CLUSTER_UNAVAILABLE").put("message",ClusterAvailability.MESSAGE));
        } catch (Exception exception) {
            // Never echo SQL, credentials, account names or financial records into responses/logs.
            request.getServletContext().log("Finance request failed: " + exception.getClass().getSimpleName());
            response.setStatus(500);
            write(response, new JSONObject().put("status", "failure")
                    .put("message", "The operation could not be completed. Please retry."));
        }
    }

    public static JSONObject result(boolean success) {
        if (!success) throw new Failure(409, "Record not found, duplicated, or no longer available for this operation.");
        return new JSONObject().put("status", "success");
    }

    public static void prepare(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    public static void write(HttpServletResponse response, JSONObject result) {
        try {
            String durability=result.optString("durability","");
            if((durability.equals("dual")||durability.equals("single"))&&durability.equals(result.optString("replicationMode", ""))) {
                response.setHeader("X-Four-Project-Durability",durability);
                response.setHeader("X-Four-Project-Degraded",Boolean.toString(durability.equals("single")));
            }
            response.getWriter().write(result.toString());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write response", exception);
        }
    }

    /** Coordinate with login so an old response cannot invalidate a newer session identity. */
    public static boolean invalidateMatchingSession(HttpServletRequest request, User expected) {
        HttpSession session = request.getSession(false);
        if (session == null) return false;
        synchronized (session) {
            try {
                User current = (User) session.getAttribute("User");
                if (current == null || !expected.getGeneration().equals(current.getGeneration())) return false;
                session.invalidate();
                return true;
            } catch (IllegalStateException alreadyInvalidated) { return false; }
        }
    }

    public static User authenticatedUser(HttpServletRequest request) {
        if (request.getServletPath().startsWith("/api/")) {
            KeyOwner owner = apiOwner(request.getHeader("Authorization"), System.getenv("IAFM_API_KEYS"));
            User user = new User();
            user.setId(owner.id()); user.setGeneration(owner.generation());
            return user;
        }
        HttpSession session = request.getSession(false);
        User user = session == null ? null : (User) session.getAttribute("User");
        if (user == null || user.getId() <= 0 || !RequestIdentity.validGeneration(user.getGeneration())) throw new Failure(401, "Please sign in again.");
        // Read requests do not write report arrays into a shared session object.
        User snapshot = new User();
        snapshot.setId(user.getId());
        snapshot.setGeneration(user.getGeneration());
        snapshot.setForename(user.getForename());
        snapshot.setSurname(user.getSurname());
        snapshot.setEmailAddress(user.getEmailAddress());
        snapshot.setCurrencyCurrentChosen(user.getCurrencyCurrentChosen());
        return snapshot;
    }

    private record KeyOwner(int id, String generation) { }
    private record KeyConfiguration(String source, java.util.Map<String, KeyOwner> owners) { }
    private static volatile KeyConfiguration cachedKeys;

    /** Parse deployment configuration once; authentication is an O(1) lookup of a full SHA-256 digest. */
    static int apiUserId(String authorization, String configuration) {
        return apiOwner(authorization, configuration).id();
    }

    static String apiUserGeneration(String authorization, String configuration) {
        return apiOwner(authorization, configuration).generation();
    }

    private static KeyOwner apiOwner(String authorization, String configuration) {
        if (configuration == null || configuration.isBlank()) throw new Failure(503, "API access is not configured.");
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new Failure(401, "A valid Bearer API key is required.");
        String token = authorization.substring(7);
        if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new Failure(401, "Invalid API key.");
        KeyConfiguration keys = cachedKeys;
        if (keys == null || !keys.source().equals(configuration)) {
            java.util.Map<String, KeyOwner> owners = new java.util.HashMap<>();
            for (String entry : configuration.split(";")) {
                String[] parts = entry.trim().split(":", -1);
                if (parts.length != 3 || !parts[0].matches("[1-9][0-9]{0,9}") || !RequestIdentity.validGeneration(parts[1]) || !parts[2].matches("[0-9a-fA-F]{64}"))
                    throw new Failure(503, "API key configuration is invalid.");
                int userId;
                try { userId = Integer.parseInt(parts[0]); }
                catch (NumberFormatException invalid) { throw new Failure(503, "API key configuration is invalid."); }
                KeyOwner owner = new KeyOwner(userId, parts[1].toLowerCase(java.util.Locale.ROOT));
                KeyOwner previous = owners.putIfAbsent(parts[2].toLowerCase(java.util.Locale.ROOT), owner);
                if (previous != null && !previous.equals(owner))
                    throw new Failure(503, "An API key must identify exactly one user.");
            }
            keys = new KeyConfiguration(configuration, java.util.Map.copyOf(owners));
            cachedKeys = keys;
        }
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        KeyOwner owner = keys.owners().get(digest);
        if (owner == null) throw new Failure(401, "Invalid API key.");
        return owner;
    }

    public static JSONObject input(HttpServletRequest request) throws IOException {
        JSONObject input = new JSONObject();
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            if (request.getContentLengthLong() > 65536) throw new Failure(413, "Request too large.");
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[2048];
            int count;
            java.io.BufferedReader reader = request.getReader();
            while ((count = reader.read(buffer)) != -1) {
                if (body.length() + count > 65536) throw new Failure(413, "Request too large.");
                body.append(buffer, 0, count);
            }
            if (!body.toString().isBlank()) input = new JSONObject(body.toString());
        }
        for (var entry : request.getParameterMap().entrySet()) {
            if (input.has(entry.getKey())) continue;
            if (entry.getKey().startsWith("filter")) {
                JSONArray values = new JSONArray();
                for (String value : entry.getValue())
                    for (String part : value.split(",")) if (!part.isBlank()) values.put(part.trim());
                input.put(entry.getKey(), values);
            } else if (entry.getValue().length == 1) input.put(entry.getKey(), entry.getValue()[0]);
        }
        return input;
    }

    public static String text(JSONObject input, String name) {
        Object value = input.opt(name);
        if (!(value instanceof String) || ((String) value).isBlank())
            throw new IllegalArgumentException("Missing text field");
        return ((String) value).trim();
    }

    public static int positiveInt(JSONObject input, String name, int fallback) {
        if (!input.has(name)) {
            if (fallback <= 0) throw new IllegalArgumentException("Required integer is missing");
            return fallback;
        }
        String value = String.valueOf(input.get(name));
        if (!value.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Expected a positive integer");
        return Integer.parseInt(value);
    }

    public static boolean isAdmin(User user) {
        return configuredAdmin(user.getId(), user.getGeneration(), System.getenv("IAFM_ADMIN_USER_IDS"));
    }

    static boolean configuredAdmin(int userId, String generation, String admins) {
        if (userId <= 0 || !RequestIdentity.validGeneration(generation) || admins == null) return false;
        String identity = userId + ":" + generation;
        for (String configured : admins.split(",")) if (configured.trim().equalsIgnoreCase(identity)) return true;
        return false;
    }

    public static void requireAdmin(User user) {
        if (!isAdmin(user)) throw new Failure(403, "Only an administrator can edit shared reference data.");
    }

    private static RequestIdentity requestIdentity(User user, boolean mutation, String path, JSONObject input) {
        java.util.List<RequestIdentity.Record> records = new java.util.ArrayList<>();
        java.util.List<RequestIdentity.Account> accounts = new java.util.ArrayList<>();
        if (mutation) {
            String table = switch (path) {
                case "/editInvestmentAsset", "/deleteInvestmentAsset" -> "ALL_INVESTMENT_ASSETS";
                case "/editNonInvestmentAsset", "/deleteNonInvestmentAsset" -> "ALL_NON_INVESTMENT_ASSETS";
                case "/editIncomeOrExpense", "/deleteIncomeOrExpense" -> "INCOME_AND_EXPENDITURE_DATA";
                case "/editInflationRate", "/deleteInflationRate" -> "ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES";
                default -> null;
            };
            if (table != null) records.add(new RequestIdentity.Record(table, positiveInt(input, "id", -1), input.optString("generation", "")));
            if ("/addIncomeOrExpense".equals(path) || "/editIncomeOrExpense".equals(path))
                accounts.add(new RequestIdentity.Account(text(input, "platform"), input.optString("platformGeneration", "")));
            if ("/transferBetweenAccounts".equals(path)) {
                accounts.add(new RequestIdentity.Account(text(input, "fromPlatform"), input.optString("fromPlatformGeneration", "")));
                accounts.add(new RequestIdentity.Account(text(input, "toPlatform"), input.optString("toPlatformGeneration", "")));
            }
        }
        return RequestIdentity.begin(user, mutation, records, accounts);
    }

    public static void checkOrigin(HttpServletRequest request) {
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) throw new Failure(403, "Cross-site request denied.");
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) throw new Failure(403, "A same-origin request is required.");
        try {
            URI source = URI.create(origin), target = URI.create(request.getRequestURL().toString());
            if (source.getHost() == null || source.getUserInfo() != null || source.getQuery() != null
                    || source.getFragment() != null || (source.getPath() != null && !source.getPath().isEmpty())
                    || !("https".equals(source.getScheme()) || "http".equals(source.getScheme())))
                throw new Failure(403, "Invalid request origin.");
            // Nginx terminates TLS on this host. Only the immediate loopback proxy may
            // describe the public scheme; direct clients cannot spoof forwarded headers.
            String peer = request.getRemoteAddr(), forwarded = request.getHeader("X-Forwarded-Proto");
            if (("127.0.0.1".equals(peer) || "::1".equals(peer) || "0:0:0:0:0:0:0:1".equals(peer))
                    && ("https".equals(forwarded) || "http".equals(forwarded))) {
                String host = request.getHeader("Host");
                if (host != null && !host.isBlank()) target = URI.create(forwarded + "://" + host);
            }
            int sourcePort = source.getPort() == -1 ? ("https".equals(source.getScheme()) ? 443 : 80) : source.getPort();
            int targetPort = target.getPort() == -1 ? ("https".equals(target.getScheme()) ? 443 : 80) : target.getPort();
            if (!java.util.Objects.equals(source.getHost(), target.getHost()) ||
                    !java.util.Objects.equals(source.getScheme(), target.getScheme()) || sourcePort != targetPort)
                throw new Failure(403, "Cross-site request denied.");
        } catch (IllegalArgumentException invalid) { throw new Failure(403, "Invalid request origin."); }
    }

    public static final class Failure extends RuntimeException {
        final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
        public int httpStatus() { return status; }
    }
}
