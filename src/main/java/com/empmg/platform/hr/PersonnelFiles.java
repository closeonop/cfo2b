package com.empmg.platform.hr;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.*;
import com.empmg.platform.security.*;
import com.empmg.platform.user.repo.UserRepo;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.crypto.Mac;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/hr/files")
@RequiredArgsConstructor
public class PersonnelFiles {
    private final HrService hr;
    private final HrCrypto crypto;
    private final EncryptionKeyHolder keys;
    private final AuditService audit;
    private final UserRepo users;
    @Value("${storage.endpoint}") private String endpoint;
    @Value("${storage.bucket}") private String bucket;
    @Value("${storage.access-key}") private String access;
    @Value("${storage.secret-key}") private String secret;
    private MinioClient client() { return MinioClient.builder().endpoint(endpoint).credentials(access,secret).build(); }
    private void allowed(AuthPrincipal p,Long employeeId) {
        hr.employee(p,employeeId);
        if(!hr.manages(p,employeeId)) throw new ApiException("Only HR, Admin or the direct manager may open a personnel file.",403);
    }
    @GetMapping public Object list(@RequestParam Long employeeId) {
        var p=SecurityUtils.current(); allowed(p,employeeId);
        var records=hr.all(p.companyId(),"document").stream().filter(r->employeeId.equals(r.employeeId())).toList();
        records.forEach(r->audit.record(p,p.companyId(),"PERSONNEL_FILE_VIEWED","DOCUMENT",r.id(),Map.of()));
        return records.stream().map(r->Map.of("id",r.id(),"filename",r.data().get("filename"),"createdAt",r.createdAt())).toList();
    }
    @PostMapping public Object upload(@RequestParam Long employeeId,@RequestParam MultipartFile file) {
        var p=SecurityUtils.current(); allowed(p,employeeId);
        String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("").replaceAll("[^\\p{L}\\p{N}._ -]","_");
        if(file.isEmpty()||file.getSize()>5*1024*1024||filename.length()>150) throw new ApiException("Choose a file under 5 MB with a short filename.");
        String extension=filename.contains(".")?filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!Set.of("pdf","png","jpg","jpeg","txt","csv").contains(extension)) throw new ApiException("Upload PDF, PNG, JPG, TXT or CSV files.");
        try {
            byte[] bytes=file.getBytes();
            boolean valid=switch(extension) {
                case "pdf" -> bytes.length>4 && new String(bytes,0,4,StandardCharsets.US_ASCII).equals("%PDF");
                case "png" -> bytes.length>8 && bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71;
                case "jpg","jpeg" -> bytes.length>3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255;
                default -> { boolean text=true; for(byte b:bytes) if(b==0) { text=false;break; } yield text; }
            };
            if(!valid) throw new ApiException("The file content does not match its extension.");
            MinioClient minio=client();
            if(!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            String object=p.companyId()+"/"+employeeId+"/"+UUID.randomUUID();
            byte[] encrypted=crypto.encrypt(bytes);
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(object).stream(new ByteArrayInputStream(encrypted),encrypted.length,-1).contentType("application/octet-stream").build());
            try { var saved=hr.store(p,"document",null,employeeId,new LinkedHashMap<>(Map.of("filename",filename,"object",object)));
                audit.record(p,p.companyId(),"PERSONNEL_FILE_UPLOADED","DOCUMENT",saved.id(),Map.of());
                return Map.of("id",saved.id(),"filename",filename);
            } catch(RuntimeException ex) { minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(object).build());throw ex; }
        } catch(ApiException ex) { throw ex; }
        catch(Exception ex) { throw new ApiException("File storage is unavailable. Start MinIO and check the storage settings.",503); }
    }
    @PostMapping("/{id}/link") public Object link(@PathVariable long id) {
        var p=SecurityUtils.current(); hr.get(p,"document",id);
        long expires=Instant.now().plusSeconds(60).getEpochSecond();
        String signature=sign(id,p.companyId(),p.userId(),expires);
        audit.record(p,p.companyId(),"PERSONNEL_DOWNLOAD_LINK","DOCUMENT",id,Map.of());
        return Map.of("path","/hr/files/"+id+"/download?company="+p.companyId()+"&actor="+p.userId()+"&expires="+expires+"&signature="+signature,"expiresIn",60);
    }
    @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@PathVariable long id,@RequestParam long company,@RequestParam long actor,@RequestParam long expires,@RequestParam String signature) {
        long now=Instant.now().getEpochSecond();
        if(expires<now||expires>now+60||!MessageDigest.isEqual(sign(id,company,actor,expires).getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8))) throw new ApiException("Download link has expired. Request a new one.",403);
        var user=users.findById(actor).filter(u->u.getCompanyId().equals(company)&&u.getStatus().equals("ACTIVE")&&!u.isPasswordChangeRequired()).orElseThrow(()->new ApiException("Access denied.",403));
        var p=new AuthPrincipal(user.getId(),company,user.getEmail(),user.getAccessRole(),user.getEmployeeId());
        var record=hr.get(p,"document",id); allowed(p,record.employeeId());
        try(var input=client().getObject(GetObjectArgs.builder().bucket(bucket).object(HrService.text(record.data(),"object")).build())) {
            byte[] bytes=crypto.decrypt(input.readAllBytes());
            audit.record(p,company,"PERSONNEL_FILE_DOWNLOADED","DOCUMENT",id,Map.of());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(HrService.text(record.data(),"filename"),StandardCharsets.UTF_8).build().toString()).body(bytes);
        } catch(Exception ex) { throw new ApiException("Could not download the file. Check file storage.",503); }
    }
    private String sign(long id,long company,long actor,long expires) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(keys.get());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((id+":"+company+":"+actor+":"+expires).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
}
