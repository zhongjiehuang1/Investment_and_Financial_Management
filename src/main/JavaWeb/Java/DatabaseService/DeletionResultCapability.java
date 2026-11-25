package DatabaseService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** A cookie-bound capability for one terminal deletion, never authentication. */
public final class DeletionResultCapability {
    private DeletionResultCapability() { }
    static String base(String session) {
        if(session==null||!session.matches("[A-Za-z0-9_-]{16,256}(?:\\.n[12])?"))return null;
        int route=session.indexOf('.');return route<0?session:session.substring(0,route);
    }
    public static String cookie(HttpServletRequest request,boolean requireAuthenticatedSession) {
        if(!request.isRequestedSessionIdFromCookie())return null;
        String requested=base(request.getRequestedSessionId());if(requested==null)return null;
        if(requireAuthenticatedSession){
            HttpSession session=request.getSession(false);
            if(session==null||!requested.equals(base(session.getId())))return null;
        }
        return requested;
    }
    static String digest(String secret,String session,String operation,String owner) {
        if(base(session)==null||!RequestIdentity.validGeneration(operation)||secret==null||secret.isBlank())throw new IllegalArgumentException("Invalid deletion capability input");
        try{
            javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("iafm/deletion-result/v1\n"+base(session)+"\n"+operation.toLowerCase(Locale.ROOT)+"\n"+owner).getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException(impossible);}
    }
    static boolean equal(String expected,String actual){
        return expected!=null&&actual!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII));
    }
}
