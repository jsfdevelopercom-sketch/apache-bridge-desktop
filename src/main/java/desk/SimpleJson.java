package desk;

import java.util.HashMap;
import java.util.Map;

public class SimpleJson {
    public static ApachePayload parsePayload(String j) {
        ApachePayload p = new ApachePayload();
        p.clientId     = val(j,"clientId");
        p.authToken    = val(j,"authToken");
        p.patientName  = val(j,"patientName");
        p.uhid         = val(j,"uhid");
        p.timestamp    = val(j,"timestamp");
        p.submissionId = val(j,"submissionId");
        String inputs  = obj(j, "apacheInputs");
        p.apacheInputs = parseObj(inputs);
        return p;
    }
    private static String val(String j, String k){
        String q = "\"" + k + "\"";
        int i = j.indexOf(q); if (i<0) return null;
        int c = j.indexOf(':', i); if (c<0) return null;
        int s = j.indexOf('"', c+1); if (s<0) return null;
        int e = j.indexOf('"', s+1); if (e<0) return null;
        return unescape(j.substring(s+1, e));
    }
    private static String obj(String j, String k){
        String q="\""+k+"\"";
        int i=j.indexOf(q); if(i<0) return "{}";
        int c=j.indexOf(':',i); if(c<0) return "{}";
        int b=j.indexOf('{',c); if(b<0) return "{}";
        int depth=0;
        for(int x=b;x<j.length();x++){
            char ch=j.charAt(x);
            if(ch=='{') depth++;
            if(ch=='}'){ depth--; if(depth==0) return j.substring(b,x+1); }
        }
        return "{}";
    }
    private static Map<String,Object> parseObj(String o){
        Map<String,Object> m=new HashMap<>();
        String s=o.trim();
        if(!s.startsWith("{")||!s.endsWith("}")) return m;
        s=s.substring(1,s.length()-1).trim();
        // naive split by commas that are not inside quotes
        StringBuilder cur=new StringBuilder();
        boolean inQ=false;
        java.util.List<String> parts=new java.util.ArrayList<>();
        for(char ch: s.toCharArray()){
            if(ch=='"') inQ=!inQ;
            if(ch==',' && !inQ){ parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        if(cur.length()>0) parts.add(cur.toString());
        for(String kv: parts){
            int c=kv.indexOf(':'); if(c<0) continue;
            String k=kv.substring(0,c).trim();
            String v=kv.substring(c+1).trim();
            if(k.startsWith("\"")&&k.endsWith("\"")) k=k.substring(1,k.length()-1);
            if(v.equals("null")) m.put(k,null);
            else if(v.equals("true")||v.equals("false")) m.put(k, Boolean.parseBoolean(v));
            else if(v.startsWith("\"")&&v.endsWith("\"")) m.put(k, unescape(v.substring(1,v.length()-1)));
            else{
                try{ m.put(k, Double.parseDouble(v)); }catch(Exception e){ m.put(k, v); }
            }
        }
        return m;
    }
    private static String unescape(String s){
        return s.replace("\\n","\n").replace("\\r","\r").replace("\\\"","\"").replace("\\\\","\\");
    }
}
