/*
 * Copyright 2010 Manuel Carrasco Moñino. (manuel_carrasco at
 * users.sourceforge.net) http://code.google.com/p/gwtupload
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package gwtupload.server.gae;

import gwtupload.server.AbstractUploadListener;
import gwtupload.server.UploadAction;
import gwtupload.server.exceptions.UploadActionException;
import gwtupload.server.exceptions.UploadCanceledException;
import gwtupload.server.gae.BlobstoreFileItemFactory.BlobstoreFileItem;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Vector;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;

import com.google.appengine.api.blobstore.BlobInfo;
import com.google.appengine.api.blobstore.BlobInfoFactory;
import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;

/**
 * <p>
 * Upload servlet for the GwtUpload library's deployed in Google App-engine using blobstore.
 * </p>
 * 
 * <p>
 * Constrains:
 * <ul>
 *   <li>It seems that the redirected servlet path must be /upload because wildcards don't work.</li>
 *   <li>After of blobstoring, our doPost receives a request size = -1.</li>
 *   <li>If the upload fails, our doPost is never called.</li>
 * </ul>
 * </p>
 * 
 * @author Manolo Carrasco Moñino
 * 
 */
public class BlobstoreUploadAction extends UploadAction {

  protected static BlobstoreService blobstoreService = BlobstoreServiceFactory.getBlobstoreService();
  protected static DatastoreService datastoreService = DatastoreServiceFactory.getDatastoreService();
  protected static BlobInfoFactory  blobInfoFactory = new BlobInfoFactory(datastoreService);
  
  private static final long serialVersionUID = -2569300604226532811L;
  
  // See constrain 1
  private String servletPath = "/upload";
  
  @Override
  public void checkRequest(HttpServletRequest request) {
    logger.debug("BLOB-STORE-SERVLET: (" + request.getSession().getId() + ") procesing a request with size: " + request.getContentLength() + " bytes.");
  }

  @Override
  public void getUploadedFile(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String parameter = request.getParameter(PARAM_SHOW);
    FileItem item = findFileItem(getSessionFileItems(request), parameter);
    if (item != null) {
      BlobInfo i = blobInfoFactory.loadBlobInfo(((BlobstoreFileItem) item).getKey());
      if (i != null) {
        logger.debug("BLOB-STORE-SERVLET: (" + request.getSession().getId() + ") getUploadedFile: " + parameter + " serving blobstore: " + i);
        blobstoreService.serve(((BlobstoreFileItem) item).getKey(), response);
      } else {
        logger.error("BLOB-STORE-SERVLET: (" + request.getSession().getId() + ") getUploadedFile: " + parameter + " file isn't in blobstore.");
      }
    } else {
      logger.info("BLOB-STORE-SERVLET: (" + request.getSession().getId() + ") getUploadedFile: " + parameter + " file isn't in session.");
      renderXmlResponse(request, response, ERROR_ITEM_NOT_FOUND);
    }
  }
  
  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);
    logger.info("Initializing Blobstore servlet." );
    if (isAppEngine()) {
      uploadDelay = 0;
      maxSize = 50 * 1024 * 1024;
      useBlobstore = true;
      logger.info("BLOB-STORE-SERVLET: init: maxSize=" + maxSize
          + ", slowUploads=" + uploadDelay + ", isAppEngine=" + isAppEngine()
          + ", useBlobstore=" + useBlobstore);
    }
  }
  
  @Override
  protected final AbstractUploadListener createNewListener(
      HttpServletRequest request) {
    return new MemCacheUploadListener(uploadDelay, request.getContentLength());
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    if (request.getParameter("blob-key") != null) {
      blobstoreService.serve(new BlobKey(request.getParameter("blob-key")), response);
    } else if (request.getParameter("redirect") != null) {
      perThreadRequest.set(request);
      String ret = TAG_ERROR;
      Map<String, String> stat = getUploadStatus(request, null, null);
      List <FileItem> items = getSessionFileItems(request);
      int nitems = 0;
      if (items != null) {
          nitems = items.size();
          for (FileItem item : getSessionFileItems(request)) {
              BlobKey k = ((BlobstoreFileItem) item).getKey();
              BlobInfo i = blobInfoFactory.loadBlobInfo(k);
              if (i != null) {
                stat.put("ctype", i.getContentType() !=null ? i.getContentType() : "unknown");
                stat.put("size", "" + i.getSize());
                stat.put("name", "" + i.getFilename());
              }
              stat.put("blobkey", k.getKeyString());
              stat.put("message", k.getKeyString());
            }
      }
      stat.put(TAG_FINISHED, "ok");
      ret = statusToString(stat);
      finish(request);
      logger.debug("BLOB-STORE-SERVLET: (" + request.getSession().getId() + ") redirect nitems=" + nitems + "\n" + ret);
      renderXmlResponse(request, response, ret, true);
      removeSessionFileItems(request);
      perThreadRequest.set(null);
    } else {
      super.doGet(request, response);
    }
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    String error = null;
    String message = null;
    
    if (request.getContentLength() > 0) {
      perThreadRequest.set(request);
      try {
        error = super.parsePostRequest(request, response);
      } catch (UploadCanceledException e) {
        finish(request);
        redirect(response, "cancelled=true");
        return;
      } catch (Exception e) {
        logger.info("BLOB-STORE-SERVLET: Exception " + e);
        error = e.getMessage();
      } finally {
        perThreadRequest.set(null);
      }
    }

    if (error != null) {
      removeSessionFileItems(request);
      redirect(response, "error=" + error);
      return;
    } 

    Map<String, BlobKey> blobs = blobstoreService.getUploadedBlobs(request);
    if (blobs != null && blobs.size() > 0) {
      List<FileItem> items = new Vector<FileItem>();
      for (Entry<String, BlobKey> e: blobs.entrySet()) {
        BlobstoreFileItem i = new BlobstoreFileItem(e.getKey(), "unknown", false, "");
        logger.info("BLOB-STORE-SERVLET: received file: " + e.getKey() + " " + e.getValue().getKeyString());
        i.setKey(e.getValue());
        items.add(i);
      }
      logger.info("BLOB-STORE-SERVLET: putting in sesssion elements -> " + items.size());
      request.getSession().setAttribute(ATTR_FILES, items);
    } else {
      error = getMessage("no_data");
    }
      
    try {
      message = executeAction(request, getSessionFileItems(request));
    } catch (UploadActionException e) {
      logger.info("ExecuteUploadActionException: " + e);
      error =  e.getMessage();
    }
      
    redirect(response, message != null ? "message=" + message : null);
  }
  
  protected void redirect(HttpServletResponse response, String params) throws IOException {
    String url = servletPath + "?redirect=true" + (params != null ? "&" + params.replaceAll("[\n\r]+", " ") : "");
    logger.info("BLOB-STORE-SERVLET: redirecting to -> : " + url);
    response.sendRedirect(url);
  }

  protected String getBlobstorePath(HttpServletRequest request) {
    String ret = blobstoreService.createUploadUrl(servletPath);
    ret = ret.replaceAll("^https*://[^/]+", "");
    logger.info("BLOB-STORE-SERVLET: generated new upload-url -> " + servletPath + " : " + ret);
    return ret;
  }

  @Override
  protected final AbstractUploadListener getCurrentListener(
      HttpServletRequest request) {
    return MemCacheUploadListener.current(request.getSession().getId());
  }

  @Override
  protected final FileItemFactory getFileItemFactory(int requestSize) {
    return new BlobstoreFileItemFactory();
  }
  
//  @SuppressWarnings("unchecked")
//  public class ProxyHttpServletRequest implements HttpServletRequest {
//
//    public class ProxyInputStream extends ServletInputStream {
//      private AbstractUploadListener listener;
//      private int total = 0;
//      private int done = 0;
//      InputStream in;
//
//      public ProxyInputStream(InputStream in, AbstractUploadListener listener, int size) {
//        super();
//        this.in = in;
//        this.listener = listener;
//        this.total = size;
//      }
//
//      @Override
//      public int read() throws IOException {
//        int ret = in.read();
//        if (ret >= 0) {
//          done ++;
//          listener.update(done, total, 0);
//        }
//        return ret;
//      }
//
//    }
//
//    HttpServletRequest r;
//    ProxyInputStream is;
//
//    public ProxyHttpServletRequest(HttpServletRequest req, AbstractUploadListener listener) throws IOException {
//      r = req;
//      is = new ProxyInputStream(r.getInputStream(), listener, r.getContentLength());
//    }
//    
//    public ServletInputStream getInputStream() throws IOException {
//      return is;
//    }
//
//    public String getAuthType() {
//      System.out.println(">>> getQueryString");
//      return r.getAuthType();
//    }
//
//    public String getContextPath() {
//      System.out.println(">>> getQueryString");
//      return r.getContextPath();
//    }
//
//    public Cookie[] getCookies() {
//      System.out.println(">>> getQueryString");
//      return r.getCookies();
//    }
//
//    public long getDateHeader(String arg0) {
//      System.out.println(">>> getQueryString");
//      return r.getDateHeader(arg0);
//    }
//
//    public String getHeader(String arg0) {
//      System.out.println(">>> getQueryString");
//      return r.getHeader(arg0);
//    }
//
//    public Enumeration getHeaderNames() {
//      System.out.println(">>> getQueryString");
//      return r.getHeaderNames();
//    }
//
//    public Enumeration getHeaders(String arg0) {
//      System.out.println(">>> getQueryString");
//      return r.getHeaders(arg0);
//    }
//
//    public int getIntHeader(String arg0) {
//      System.out.println(">>> getQueryString");
//      return r.getIntHeader(arg0);
//    }
//
//    public String getMethod() {
//      System.out.println(">>> getQueryString");
//      return r.getMethod();
//    }
//
//    public String getPathInfo() {
//      System.out.println(">>> getQueryString");
//      return r.getPathInfo();
//    }
//
//    public String getPathTranslated() {
//      System.out.println(">>> getQueryString");
//      return r.getPathTranslated();
//    }
//
//    public String getQueryString() {
//      System.out.println(">>> getQueryString");
//      return r.getQueryString();
//    }
//
//    public String getRemoteUser() {
//      return r.getRemoteUser();
//    }
//
//    public String getRequestURI() {
//      System.out.println(">>> getRequestURI");
//      
//      return r.getRequestURI();
//    }
//
//    public StringBuffer getRequestURL() {
//      System.out.println(">>> getRequestURL");
//      return r.getRequestURL();
//    }
//
//    public String getRequestedSessionId() {
//      return r.getRequestedSessionId();
//    }
//
//    public String getServletPath() {
//      System.out.println(">>> getServletPath");
//      
//      return r.getServletPath();
//    }
//
//    public HttpSession getSession() {
//      return r.getSession();
//    }
//
//    public HttpSession getSession(boolean arg0) {
//      return r.getSession(arg0);
//    }
//
//    public Principal getUserPrincipal() {
//      return r.getUserPrincipal();
//    }
//
//    public boolean isRequestedSessionIdFromCookie() {
//      return r.isRequestedSessionIdFromCookie();
//    }
//
//    public boolean isRequestedSessionIdFromURL() {
//      return r.isRequestedSessionIdFromURL();
//    }
//
//    @SuppressWarnings("deprecation")
//    public boolean isRequestedSessionIdFromUrl() {
//      return r.isRequestedSessionIdFromUrl();
//    }
//
//    public boolean isRequestedSessionIdValid() {
//      return r.isRequestedSessionIdValid();
//    }
//
//    public boolean isUserInRole(String arg0) {
//      return r.isUserInRole(arg0);
//    }
//
//    public Object getAttribute(String arg0) {
//      return r.getAttribute(arg0);
//    }
//
//    public Enumeration getAttributeNames() {
//      return r.getAttributeNames();
//    }
//
//    public String getCharacterEncoding() {
//      return r.getCharacterEncoding();
//    }
//
//    public int getContentLength() {
//      return r.getContentLength();
//    }
//
//    public String getContentType() {
//      return r.getContentType();
//    }
//
//    public String getLocalAddr() {
//      return r.getLocalAddr();
//    }
//
//    public String getLocalName() {
//      return r.getLocalName();
//    }
//
//    public int getLocalPort() {
//      return r.getLocalPort();
//    }
//
//    public Locale getLocale() {
//      return r.getLocale();
//    }
//
//    public Enumeration getLocales() {
//      return r.getLocales();
//    }
//
//    public String getParameter(String arg0) {
//      return r.getParameter(arg0);
//    }
//
//    public Map getParameterMap() {
//      return r.getParameterMap();
//    }
//
//    public Enumeration getParameterNames() {
//      return r.getParameterNames();
//    }
//
//    public String[] getParameterValues(String arg0) {
//      return r.getParameterValues(arg0);
//    }
//
//    public String getProtocol() {
//      return r.getProtocol();
//    }
//
//    public BufferedReader getReader() throws IOException {
//      return r.getReader();
//    }
//
//    @SuppressWarnings("deprecation")
//    public String getRealPath(String arg0) {
//      return r.getRealPath(arg0);
//    }
//
//    public String getRemoteAddr() {
//      return r.getRemoteAddr();
//    }
//
//    public String getRemoteHost() {
//      return r.getRemoteHost();
//    }
//
//    public int getRemotePort() {
//      return r.getRemotePort();
//    }
//
//    public RequestDispatcher getRequestDispatcher(String arg0) {
//      return r.getRequestDispatcher(arg0);
//    }
//
//    public String getScheme() {
//      return r.getScheme();
//    }
//
//    public String getServerName() {
//      return r.getServerName();
//    }
//
//    public int getServerPort() {
//      return 0;
//    }
//
//    public boolean isSecure() {
//      return r.isSecure();
//    }
//
//    public void removeAttribute(String arg0) {
//      r.removeAttribute(arg0);
//    }
//
//    public void setAttribute(String arg0, Object arg1) {
//      r.setAttribute(arg0, arg1);
//    }
//
//    public void setCharacterEncoding(String arg0)
//        throws UnsupportedEncodingException {
//      r.setCharacterEncoding(arg0);
//    }
//
//  }
  
}
