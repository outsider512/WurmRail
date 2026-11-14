package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Paid internal credits and explicit links; refill journals refer to actual native item IDs. */
public final class SteamSupplies {
    public static final int MAX_MACHINES=30000,MAX_DEBITS=4097;
    public static final class Debit {
        public final long itemId,parentId,microPerGram;
        public final int templateId,before,after;
        public final boolean water;
        public Debit(long item,long parent,int template,int before,int after,long energyPerGram,boolean water){if(item<=0||parent<=0||template<=0||before<=0||after<0||after>=before||energyPerGram<0||water&&energyPerGram!=0||!water&&energyPerGram<=0)throw new IllegalArgumentException("Invalid native steam debit.");itemId=item;parentId=parent;templateId=template;this.before=before;this.after=after;microPerGram=energyPerGram;this.water=water;}
    }
    public static final class Supply {
        public final long machineId,ownerId,revision,fuelId,waterId,energyMicro,waterMillilitres,workToken,workEnergy,workWater;
        /** 0=no native refill;1=record written before debit;2=credits recorded, empty-item cleanup pending. */
        public final int phase;
        public final boolean fault;
        public final String status;
        public final List<Debit> debits;
        public Supply(long machine,long owner,long revision,long fuel,long water,long energy,long ml,long workToken,long workEnergy,long workWater,int phase,boolean fault,String status,List<Debit> debits){
            if(machine<=0||owner<=0||revision<0||fuel<0||water<0||energy<0||ml<0||workToken<0||workEnergy<0||workWater<0||phase<0||phase>2||debits==null||debits.size()>MAX_DEBITS||workToken==0&&(workEnergy!=0||workWater!=0)||phase==0&&!debits.isEmpty()||phase!=0&&debits.isEmpty())throw new IllegalArgumentException("Invalid steam supply state.");
            machineId=machine;ownerId=owner;this.revision=revision;fuelId=fuel;waterId=water;energyMicro=energy;waterMillilitres=ml;this.workToken=workToken;this.workEnergy=workEnergy;this.workWater=workWater;this.phase=phase;this.fault=fault;this.status=line(status);this.debits=Collections.unmodifiableList(new ArrayList<Debit>(debits));
        }
        public Supply balances(long energy,long ml,long token,long reservedEnergy,long reservedWater,int phase,boolean fault,String status,List<Debit> debits){return new Supply(machineId,ownerId,revision,fuelId,waterId,energy,ml,token,reservedEnergy,reservedWater,phase,fault,status,debits);}
    }
    private final Map<Long,Supply> data=new LinkedHashMap<Long,Supply>();
    public final Map<Long,Supply> supplies=Collections.unmodifiableMap(data);
    private long revision,sequence;
    public Supply get(long id){return data.get(id);}
    public long nextRevision(){if(revision==Long.MAX_VALUE)throw new IllegalStateException("Steam revision limit reached.");return ++revision;}
    public long nextToken(){if(sequence==Long.MAX_VALUE)throw new IllegalStateException("Steam transaction limit reached.");return ++sequence;}
    public void put(Supply supply){if(supply==null||data.size()>=MAX_MACHINES&&!data.containsKey(supply.machineId))throw new IllegalArgumentException("Invalid steam supply registry.");data.put(supply.machineId,supply);revision=Math.max(revision,supply.revision);sequence=Math.max(sequence,supply.workToken);}
    public static Supply initial(long machine,long owner,long revision,long fuel,long water){return new Supply(machine,owner,revision,fuel,water,0,0,0,0,0,0,false,"NO FUEL: select and stock Fuel Supply.",Collections.<Debit>emptyList());}
    private static String line(String v){if(v==null||v.trim().isEmpty()||v.length()>512)throw new IllegalArgumentException("A one-line supply status is required.");for(int i=0;i<v.length();i++)if(Character.isISOControl(v.charAt(i)))throw new IllegalArgumentException("A one-line supply status is required.");return v;}
    public static SteamSupplies load(Path path)throws IOException {
        // Old balances must never be replayed after native supplies were spent.
        if(!Files.exists(path)){if(Files.exists(backup(path)))throw new IOException("Steam supplies primary file is missing; preserve its backup for inspection: "+path);return new SteamSupplies();}
        return read(path);
    }
    private static SteamSupplies read(Path path)throws IOException {
        try{Properties p=new Properties();try(InputStream in=Files.newInputStream(path)){p.load(in);}if(!"1".equals(p.getProperty("format"))||!checksum(p).equals(p.getProperty("checksum")))throw new IOException("Steam supply checksum/version is invalid: "+path);int count=Integer.parseInt(required(p,"machines"));if(count<0||count>MAX_MACHINES)throw new IllegalArgumentException("Invalid supply count.");SteamSupplies result=new SteamSupplies();Set<String> expected=new HashSet<String>(Arrays.asList("format","machines","revision","sequence","checksum"));
            for(int i=0;i<count;i++){String k="s."+i+".";String[] fields={"machine","owner","revision","fuel","water","energy","ml","token","reservedEnergy","reservedWater","phase","fault","status","debits"};for(String field:fields)expected.add(k+field);String fault=required(p,k+"fault");if(!fault.equals("true")&&!fault.equals("false"))throw new IllegalArgumentException("Invalid steam fault.");int n=Integer.parseInt(required(p,k+"debits"));if(n<0||n>MAX_DEBITS)throw new IllegalArgumentException("Invalid debit count.");List<Debit> debits=new ArrayList<Debit>();Set<Long> ids=new HashSet<Long>();for(int j=0;j<n;j++){String d=k+"d."+j;expected.add(d);String[] f=required(p,d).split(",",-1);if(f.length!=7||!f[6].equals("true")&&!f[6].equals("false"))throw new IllegalArgumentException("Invalid debit record.");Debit debit=new Debit(Long.parseLong(f[0]),Long.parseLong(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Long.parseLong(f[5]),Boolean.parseBoolean(f[6]));if(!ids.add(debit.itemId))throw new IllegalArgumentException("Duplicate debit item.");debits.add(debit);}
                Supply supply=new Supply(value(p,k+"machine"),value(p,k+"owner"),value(p,k+"revision"),value(p,k+"fuel"),value(p,k+"water"),value(p,k+"energy"),value(p,k+"ml"),value(p,k+"token"),value(p,k+"reservedEnergy"),value(p,k+"reservedWater"),Integer.parseInt(required(p,k+"phase")),Boolean.parseBoolean(fault),required(p,k+"status"),debits);if(result.get(supply.machineId)!=null)throw new IllegalArgumentException("Duplicate steam consumer.");result.put(supply);}
            long revision=value(p,"revision"),sequence=value(p,"sequence");if(revision<result.revision||sequence<result.sequence)throw new IllegalArgumentException("Steam high-water marks are invalid.");result.revision=revision;result.sequence=sequence;if(!expected.equals(p.stringPropertyNames()))throw new IllegalArgumentException("Unexpected steam supply fields.");return result;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid steam supplies: "+path,invalid);}
    }
    public void save(Path path)throws IOException {Properties p=new Properties();p.setProperty("format","1");p.setProperty("machines",Integer.toString(data.size()));p.setProperty("revision",Long.toString(revision));p.setProperty("sequence",Long.toString(sequence));int i=0;for(Supply s:data.values()){String k="s."+(i++)+".";put(p,k,"machine",s.machineId);put(p,k,"owner",s.ownerId);put(p,k,"revision",s.revision);put(p,k,"fuel",s.fuelId);put(p,k,"water",s.waterId);put(p,k,"energy",s.energyMicro);put(p,k,"ml",s.waterMillilitres);put(p,k,"token",s.workToken);put(p,k,"reservedEnergy",s.workEnergy);put(p,k,"reservedWater",s.workWater);put(p,k,"phase",s.phase);p.setProperty(k+"fault",Boolean.toString(s.fault));p.setProperty(k+"status",s.status);put(p,k,"debits",s.debits.size());int j=0;for(Debit d:s.debits)p.setProperty(k+"d."+(j++),d.itemId+","+d.parentId+","+d.templateId+","+d.before+","+d.after+","+d.microPerGram+","+d.water);}
        p.setProperty("checksum",checksum(p));Path absolute=path.toAbsolutePath();Files.createDirectories(absolute.getParent());Path temporary=Files.createTempFile(absolute.getParent(),"steam-supplies-",".tmp");try{try(OutputStream out=Files.newOutputStream(temporary)){p.store(out,"WurmRail paid steam supply buffers and native debit journal");}if(Files.exists(path)){boolean valid=true;try{read(path);}catch(IOException invalid){valid=false;}if(valid)Files.copy(path,backup(path),StandardCopyOption.REPLACE_EXISTING);}try{Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temporary);}}
    private static void put(Properties p,String k,String f,long v){p.setProperty(k+f,Long.toString(v));}
    private static long value(Properties p,String k){return Long.parseLong(required(p,k));}
    private static String required(Properties p,String k){String v=p.getProperty(k);if(v==null)throw new IllegalArgumentException("Missing "+k);return v;}
    private static Path backup(Path p){return p.resolveSibling(p.getFileName()+".bak");}
    private static String checksum(Properties p){try{MessageDigest d=MessageDigest.getInstance("SHA-256");for(String k:new TreeSet<String>(p.stringPropertyNames()))if(!k.equals("checksum")){String v=p.getProperty(k);d.update((k.length()+":"+k+v.length()+":"+v+"\n").getBytes(StandardCharsets.UTF_8));}StringBuilder hex=new StringBuilder();for(byte b:d.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
