package com.example.pagecache.controller;

import com.example.pagecache.util.Md5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;

@RestController
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        long size = file.getSize();
        long fdBefore = countOpenFd();
        long t0 = System.currentTimeMillis();

        try {
            InputStream is = file.getInputStream();
            String md5 = Md5Util.md5Hex(is);
            long cost = System.currentTimeMillis() - t0;
            long fdAfter = countOpenFd();

            log.info("[UPLOAD] file={}, size={}KB, md5={}, md5Cost={}ms, fdBefore={}, fdAfter={}, fdLeak={}",
                    originalFilename, size / 1024, md5, cost, fdBefore, fdAfter, (fdAfter - fdBefore));

            return "upload success, md5=" + md5 + ", size=" + size;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - t0;
            log.error("[UPLOAD] file={}, size={}KB, cost={}ms, error={}",
                    originalFilename, size / 1024, cost, e.getMessage());
            return "upload failed: " + e.getMessage();
        }
    }

    private long countOpenFd() {
        try {
            File fdDir = new File("/proc/self/fd");
            if (fdDir.exists() && fdDir.isDirectory()) {
                String[] list = fdDir.list();
                return list == null ? 0 : list.length;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
