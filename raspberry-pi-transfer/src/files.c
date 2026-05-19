/*
 * files.c - File Management
 * Safe file I/O with path validation
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

#include "files.h"
#include "utils.h"

/**
 * Create output directory if it doesn't exist
 */
int files_create_output_dir(const char *path) {
    if (path == NULL || strlen(path) == 0) {
        log_error("Invalid directory path");
        return -1;
    }
    
    /* Try to create directory (OK if already exists) */
    #ifdef _WIN32
        mkdir(path);
    #else
        mkdir(path, 0755);
    #endif
    
    /* Verify directory exists */
    struct stat st;
    if (stat(path, &st) != 0) {
        log_error("Failed to create/access directory: %s", path);
        return -1;
    }
    
    if (!S_ISDIR(st.st_mode)) {
        log_error("Path is not a directory: %s", path);
        return -1;
    }
    
    log_info("Output directory: %s", path);
    return 0;
}

/**
 * Validate filename - prevent path traversal attacks
 */
int files_validate_filename(const char *filename) {
    if (filename == NULL || strlen(filename) == 0) {
        log_error("Invalid filename");
        return -1;
    }
    
    /* Check for path traversal sequences */
    if (strstr(filename, "..") != NULL) {
        log_error("Filename contains '..': %s", filename);
        return -1;
    }
    
    if (strchr(filename, '/') != NULL || strchr(filename, '\\') != NULL) {
        log_error("Filename contains path separator: %s", filename);
        return -1;
    }
    
    /* Check for absolute paths */
    if (filename[0] == '/' || filename[0] == '\\') {
        log_error("Filename is absolute path: %s", filename);
        return -1;
    }
    
    return 0;
}

/**
 * Build full path safely
 */
int files_build_path(char *full_path, size_t path_size,
                     const char *directory, const char *filename) {
    if (full_path == NULL || directory == NULL || filename == NULL) {
        return -1;
    }
    
    if (files_validate_filename(filename) != 0) {
        return -1;
    }
    
    /* Build path: directory/filename */
    size_t dir_len = strlen(directory);
    size_t file_len = strlen(filename);
    
    if (dir_len + 1 + file_len + 1 > path_size) {
        log_error("Path too long");
        return -1;
    }
    
    strcpy(full_path, directory);
    
    /* Add separator if needed */
    if (full_path[dir_len - 1] != '/' && full_path[dir_len - 1] != '\\') {
        #ifdef _WIN32
            strcat(full_path, "\\");
        #else
            strcat(full_path, "/");
        #endif
    }
    
    strcat(full_path, filename);
    
    return 0;
}

/**
 * Write decrypted file to disk
 */
int files_write(const char *directory, const char *filename,
                const uint8_t *data, size_t size) {
    FILE *fp = NULL;
    char full_path[512];
    int ret = -1;
    
    if (directory == NULL || filename == NULL || data == NULL) {
        log_error("Invalid parameters");
        return -1;
    }
    
    /* Build full path */
    if (files_build_path(full_path, sizeof(full_path), directory, filename) != 0) {
        return -1;
    }
    
    /* Open file for writing */
    fp = fopen(full_path, "wb");
    if (fp == NULL) {
        log_error("Failed to open file for writing: %s", full_path);
        return -1;
    }
    
    /* Write data */
    size_t written = fwrite(data, 1, size, fp);
    if (written != size) {
        log_error("Failed to write all data: wrote %zu/%zu bytes", written, size);
        goto cleanup;
    }
    
    log_info("File written: %s (%zu bytes)", full_path, size);
    ret = 0;
    
cleanup:
    if (fp != NULL) {
        fclose(fp);
    }
    
    return ret;
}
