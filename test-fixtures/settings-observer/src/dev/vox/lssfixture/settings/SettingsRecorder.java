package dev.vox.lssfixture.settings;

import java.io.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** MC-free bounded evidence writer, boot-loaded for Paper plugin classloader isolation. */
public final class SettingsRecorder {
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(4096);
    private static volatile boolean running, overflow;
    private static Thread writer;
    private static String run;
    private static long samples;
    private static final Map<Object,Set<Long>> tracked = new IdentityHashMap<>();
    public static void start() {
        run=System.getProperty("lss.rig.runId","");
        Path root=Path.of(System.getProperty("lss.rig.evidence",""));
        if(!run.matches("[A-Za-z0-9_-]+") || !root.isAbsolute() || Files.isSymbolicLink(root))
            throw new IllegalStateException("owned run evidence required");
        running=true;
        writer=new Thread(()->{
            try(BufferedWriter out=Files.newBufferedWriter(root.resolve("settings-events.jsonl"),StandardOpenOption.CREATE_NEW)) {
                while(running || !QUEUE.isEmpty()) {
                    String line=QUEUE.poll(200,TimeUnit.MILLISECONDS);
                    if(line!=null){out.write(line);out.newLine();out.flush();}
                }
                out.write("{\"event\":\"closed\",\"overflow\":"+overflow+"}\n");
            }catch(Exception e){overflow=true;}
        },"LSS-RigSettingsEvidence");
        writer.setDaemon(true);writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{running=false;try{writer.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
        emit("ready",Map.of());
    }
    static Object field(Object owner,String name) throws Exception {
        Class<?> type=owner instanceof Class<?> c ? c : owner.getClass();
        for(Class<?> c=type;c!=null;c=c.getSuperclass())try{
            Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(owner instanceof Class<?> ? null : owner);
        }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    static Object call(Object owner,String name) throws Exception {
        Method method=owner.getClass().getMethod(name);method.setAccessible(true);return method.invoke(owner);
    }
    static void emit(String event,Map<String,Object> fields) {
        StringBuilder row=new StringBuilder("{\"event\":\"").append(event).append("\",\"run_id\":\"").append(run)
                .append("\",\"time_ns\":").append(System.nanoTime()).append(",\"time_ms\":").append(System.currentTimeMillis())
                .append(",\"thread_id\":").append(Thread.currentThread().threadId());
        fields.forEach((key,value)->row.append(",\"").append(key).append("\":").append(value instanceof Number || value instanceof Boolean ? value : "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ")+"\""));
        if(!QUEUE.offer(row.append('}').toString()))overflow=true;
    }
    public static void applied(String kind,String sha){emit("applied",Map.of("kind",kind,"sha256",sha));}
    public static void applied(String kind,String sourceSha,String transformedSha,String validation) {
        emit("applied",Map.of("kind",kind,"sha256",sourceSha,"transformed_sha256",transformedSha,"validation",validation));
    }
    public static void transformFailure(String kind,String expected,String actual,String reason) {
        emit("failure",Map.of("code","transform_"+kind,"expected_sha256",String.valueOf(expected),"actual_sha256",actual,
                "reason",reason.substring(0,Math.min(reason.length(),256))));
    }
    public static void failure(String code){emit("failure",Map.of("code",code));}
    static Map<String,Object> generation(Object gen) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("enabled",field(gen,"admissionEnabled"));row.put("revision",field(gen,"policyRevision"));
        row.put("active",call(gen,"getActiveCount"));row.put("submitted",call(gen,"getTotalSubmitted"));
        row.put("completed",call(gen,"getTotalCompleted"));row.put("timeouts",call(gen,"getTotalTimeouts"));
        row.put("removed",call(gen,"getTotalRemovedInFlight"));
        if(gen.getClass().getName().contains("Paper"))row.put("ticket_owner","moonrise");
        else {
            row.put("ticket_owner","lss");
            row.put("deferred",((Map<?,?>)field(field(gen,"deferredReleases"),"pending")).size());
            for(Object active:((Map<?,?>)field(gen,"active")).values()) {
                Object level=field(active,"level"), pos=field(active,"pos");
                long x=((Number)call(pos,"x")).longValue(), z=((Number)call(pos,"z")).longValue();
                Set<Long> positions=tracked.computeIfAbsent(level,ignored->new HashSet<>());
                if(positions.size()>=8192)throw new IllegalStateException("tracked ticket budget");
                positions.add((x&0xffffffffL)|((z&0xffffffffL)<<32));
            }
            int ticketCount=0, trackedCount=0;
            if(Boolean.FALSE.equals(row.get("enabled"))) {
                Object ownTicket=field(gen.getClass(),"LSS_GEN_TICKET");
                Class<?> ticketStorage=Class.forName("net.minecraft.world.level.TicketStorage",false,gen.getClass().getClassLoader());
                Object storageType=field(ticketStorage,"TYPE");
                for(var entry:tracked.entrySet()) {
                    Object storage=call(entry.getKey(),"getDataStorage"), tickets=null;
                    for(Method method:storage.getClass().getMethods())if(method.getName().equals("computeIfAbsent") && method.getParameterCount()==1) {
                        tickets=method.invoke(storage,storageType);break;
                    }
                    if(tickets==null)throw new IllegalStateException("ticket storage unavailable");
                    for(long position:entry.getValue()) {
                        trackedCount++;
                        for(Object ticket:(Iterable<?>)tickets.getClass().getMethod("getTickets",long.class).invoke(tickets,position))
                            if(call(ticket,"getType")==ownTicket)ticketCount++;
                    }
                }
                row.put("tracked_positions",trackedCount);row.put("tracked_tickets",ticketCount);
            }
        }
        return row;
    }
    public static void policy(Object gen) {
        try{emit("generation_policy",generation(gen));}catch(Exception e){failure("generation_observer_"+e.getClass().getSimpleName());}
    }
    public static void sample(Object service) {
        // Exact tick-return callback is the service owner on Fabric, Paper and Folia.
        if(++samples%2!=0)return;
        try {
            Object gen=call(service,"getGenerationService");if(gen==null)return;
            Map<String,Object> row=generation(gen);
            row.put("players",((Map<?,?>)call(service,"getPlayers")).size());
            row.put("sent",call(call(service,"getTickDiag"),"getTotalSectionsSent"));
            row.put("owner","product_tick_return");
            Object reader=call(service,"getDiskReader");
            row.put("reader_threads",field(reader,"threadCount"));
            Object config=service.getClass().getName().contains("Paper") ? call(service,"getConfig")
                    : field(Class.forName("dev.vox.lss.config.LSSServerConfig",false,service.getClass().getClassLoader()),"CONFIG");
            Object handle=call(config,"settingsHandle");
            Object state=call(handle,"state");
            row.put("reader_restart_pending",((Set<?>)call(state,"pendingRestart")).contains("storage.disk.reader_threads"));

            if(!service.getClass().getName().contains("Paper")) {
                Object backfill=call(service,"getStoreBackfill");
                row.put("backfill_present",backfill!=null);
                if(backfill!=null) {
                    Object policy=field(backfill,"policy");
                    row.put("backfill_enabled",call(policy,"enabled"));
                    row.put("backfill_rate",call(policy,"columnsPerSecond"));
                    row.put("backfill_revision",call(policy,"revision"));
                    row.put("backfill_running",call(backfill,"isRunning"));
                    Thread worker=(Thread)field(backfill,"worker");row.put("backfill_worker",worker==null?0:worker.threadId());
                    row.put("backfill_status",call(backfill,"statusLine"));
                }
            }
            emit("sample",row);
        }catch(Exception e){failure("sample_observer_"+e.getClass().getSimpleName());}
    }
}
