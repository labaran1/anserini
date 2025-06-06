/*
 * Anserini: A Lucene toolkit for reproducible information retrieval research
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.util;

import me.tongfei.progressbar.ProgressBar;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CountingInputStream;

import io.anserini.index.IndexInfo;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public class PrebuiltIndexHandler {
  private static final String DEFAULT_CACHE_DIR = Path.of(System.getProperty("user.home"), ".cache", "pyserini", "indexes").toString();
  private static final String CACHE_DIR_PROPERTY = "anserini.index.cache";
  private static final String CACHE_DIR_ENV = "ANSERINI_INDEX_CACHE";

  private String indexName;
  private String saveRootPath;
  private IndexInfo info = null;
  private Path indexFolderPath = null;
  private boolean initialized = false;
  private Path savePath;

  public PrebuiltIndexHandler(String indexName) {
    this.indexName = indexName;
    this.saveRootPath = getCache();
  }
  
  /**
   * Creates a PrebuiltIndexHandler with a custom, user specified cache directory.
   * This is good if you don't want to use the default cache directory,
   * at ~/.cache/pyserini/indexes.
   * Specify it through the environment variable $ANSERINI_INDEX_CACHE.
   * Alternatively, specify it through the system property $anserini.index.cache.
   * We recommend specifying it through the environment variable as it's easier to manage:
   * but if left unset, fallbacks are appropriate.
   * 
   * @param indexName the name of the index
   * @param cacheDir the custom cache directory to use
   */
  public PrebuiltIndexHandler(String indexName, String cacheDir) {
    this.indexName = indexName;
    this.saveRootPath = cacheDir;
  }

  private String getCache() {
    String cacheDir = System.getProperty(CACHE_DIR_PROPERTY);
    
    if (cacheDir == null || cacheDir.isEmpty()) {
      cacheDir = System.getenv(CACHE_DIR_ENV);
    }
    
    if (cacheDir == null || cacheDir.isEmpty()) {
      cacheDir = DEFAULT_CACHE_DIR;
    }
    
    return cacheDir;
  }

  private static boolean checkFileExist(Path path) {
    return path.toFile().exists();
  }

  public boolean checkIndexFileExist() {
    /*
     * Check if the index file exists. If the index file exists, return true.
     * Otherwise, return false.
     */
    if (checkFileExist(savePath) || checkFileExist(Path.of(savePath.toString().replace(".gz", "")))
        || checkFileExist(Path.of(savePath.toString().replace(".tar.gz", "")))
        || checkFileExist(Path.of(savePath.toString() + "." + info.md5))
        || checkFileExist(Path.of(savePath.toString().replace(".gz", "") + "." + info.md5))
        || checkFileExist(Path.of(savePath.toString().replace(".tar.gz", "") + "." + info.md5))) {
      return true;
    }
    return false;
  }

  private static IndexInfo getIndexInfo(String indexName) {
    /*
     * Get the index info from the index name.
     */
    try {
      return IndexInfo.get(indexName);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Index not found!" + e.getMessage());
    }
  }

  private static boolean checkMD5(InputStream st, String md5) throws IOException {
    /*
     * Check the MD5 checksum of the index file.
     */
    String generatedChecksum = DigestUtils.md5Hex(st);
    return generatedChecksum.equals(md5);
  }

  public void initialize() {
    if (initialized) {
      return;
    }
    info = getIndexInfo(indexName);
    // check if saveRootPath exists
    if (!checkFileExist(Path.of(saveRootPath))) {
      try {
        Files.createDirectories(Path.of(saveRootPath));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    savePath = Path.of(saveRootPath, info.filename);
    initialized = true;
  }

  public void download() throws IOException, URISyntaxException {
    /*
     * Download the index file to the save path. If the file already exists, do
     * nothing. If the file does not exist, download the file and check the MD5
     * checksum.
     */
    if (!initialized) {
      throw new IllegalStateException("Handler not initialized!");
    }
    if (checkIndexFileExist()) {
      System.out.println("Index file already exists! Skip downloading.");
      return;
    }

    URL url = new URI(info.urls[0]).toURL();
    System.out.println("Downloading index from: " + info.urls[0]);
    HttpURLConnection httpConnection = (HttpURLConnection) (url.openConnection());
    long completeFileSize = httpConnection.getContentLengthLong();

    try (InputStream inputStream = url.openStream();
        CountingInputStream cis = new CountingInputStream(inputStream);
        FileOutputStream fileOS = new FileOutputStream(savePath.toFile());
        ProgressBar pb = new ProgressBar(indexName, Math.floorDiv(completeFileSize, 1000))) {

      pb.setExtraMessage("Downloading...");

      new Thread(() -> {
        try {
          IOUtils.copyLarge(cis, fileOS);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }).start();

      while (cis.getByteCount() < completeFileSize) {
        pb.stepTo(Math.floorDiv(cis.getByteCount(), 1000));
      }

      pb.stepTo(Math.floorDiv(cis.getByteCount(), 1000));
      pb.close();

      InputStream is = Files.newInputStream(savePath);
      if (!checkMD5(is, info.md5)) {
        throw new IOException("MD5 check failed!");
      }
    }
  }

  public String decompressIndex() throws Exception {
    /*
     * Decompress the tar.gz or tar index file to an archive folder. If the folder
     * already exists, do nothing.
     */
    if (!initialized) {
      throw new IllegalStateException("Handler not initialized!");
    }
    if (!checkIndexFileExist()) {
      throw new Exception("Index file does not exist!");
    }

    String indexFolder = savePath.toString().replace(".tar.gz", "");
    if (checkFileExist(Path.of(indexFolder + "." + info.md5))) {
      System.out.println("Index folder already exists!");
      return indexFolder + "." + info.md5;
    }
    System.out.println("Decompressing index...");

    if (checkFileExist(Path.of(savePath.toString()))) {
      ProcessBuilder pbGZIP = new ProcessBuilder("gzip", "-d", savePath.toString());
      Process pGZIP = pbGZIP.start();
      pGZIP.waitFor();
    }

    if (checkFileExist(Path.of(savePath.toString().replace(".gz", "")))) {
      ProcessBuilder pbTAR = new ProcessBuilder("tar", "-xvf",
          savePath.toString().substring(0, savePath.toString().length() - 3), "-C", saveRootPath);
      Process pTar = pbTAR.start();
      pTar.waitFor();

      // delete the tar file for saving space
      Files.delete(Path.of(savePath.toString().replace(".gz", "")));
    }

    System.out.println("Index decompressed successfully!");

    // postpend md5 to decompressed index
    Path oldIndexPath = Path.of(indexFolder);
    indexFolder += "." + info.md5;
    this.indexFolderPath = Path.of(indexFolder);
    if (!checkFileExist(this.indexFolderPath)) {
      Files.move(oldIndexPath, this.indexFolderPath);
    }
    return indexFolder;
  }

  public Path getIndexFolderPath() {
    return this.indexFolderPath;
  }
}
