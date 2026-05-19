#ifndef FILES_H
#define FILES_H

#include <stdint.h>
#include <stddef.h>

/* File Management - Save decrypted files */

/* Create output directory if it doesn't exist */
int files_create_output_dir(const char *path);

/* Validate filename (prevent path traversal) */
int files_validate_filename(const char *filename);

/* Write decrypted file to disk */
int files_write(const char *directory, const char *filename, 
                const uint8_t *data, size_t size);

/* Build full path safely */
int files_build_path(char *full_path, size_t path_size,
                     const char *directory, const char *filename);

#endif
