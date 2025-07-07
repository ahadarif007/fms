package com.bracit.fms.fms.repository;

import com.bracit.fms.fms.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, String> {

    List<FileEntity> findByFileType(String fileType);

    List<FileEntity> findByProvider(String provider);

    @Query("SELECT f FROM FileEntity f WHERE f.fileType = :fileType AND f.provider = :provider")
    List<FileEntity> findByFileTypeAndProvider(@Param("fileType") String fileType, @Param("provider") String provider);

    boolean existsByIdAndProvider(String id, String provider);
}