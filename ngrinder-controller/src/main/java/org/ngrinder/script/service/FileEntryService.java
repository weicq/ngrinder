/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.script.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ngrinder.common.util.PathUtils;
import org.ngrinder.common.util.ThreadUtils;
import org.ngrinder.common.util.UrlUtils;
import org.ngrinder.infra.config.Config;
import org.ngrinder.model.User;
import org.ngrinder.script.handler.ProjectHandler;
import org.ngrinder.script.handler.ScriptHandler;
import org.ngrinder.script.handler.ScriptHandlerFactory;
import org.ngrinder.script.model.FileEntry;
import org.ngrinder.script.model.FileType;
import org.ngrinder.script.model.HAR;
import org.ngrinder.script.model.Request;
import org.ngrinder.script.repository.FileEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.io.fs.FSHook;
import org.tmatesoft.svn.core.internal.io.fs.FSHookEvent;
import org.tmatesoft.svn.core.internal.io.fs.FSHooks;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNRevision;

import javax.annotation.PostConstruct;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.Collections.unmodifiableList;
import static org.ngrinder.common.util.CollectionUtils.buildMap;
import static org.ngrinder.common.util.CollectionUtils.newHashMap;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * File entry service class.
 * <p>
 * This class is responsible for creating user svn repository whenever a user is
 * created and connect the user to the underlying svn.
 *
 * @author JunHo Yoon
 * @since 3.0
 */
@Service
public class FileEntryService {

	private static final Logger LOG = LoggerFactory.getLogger(FileEntryService.class);

	private SVNClientManager svnClientManager;

	@Autowired
	private Config config;

	@Autowired
	@Qualifier("cacheManager")
	private CacheManager cacheManager;

	@SuppressWarnings("SpringJavaAutowiringInspection")
	@Autowired
	private FileEntryRepository fileEntityRepository;

	@Autowired
	private ScriptHandlerFactory scriptHandlerFactory;

	private Cache fileEntryCache;

	/**
	 * Initialize {@link FileEntryService}.
	 */
	@PostConstruct
	public void init() {
		// Add cache invalidation hook.
		FSHooks.registerHook(new FSHook() {
			@Override
			public void onHook(FSHookEvent event) throws SVNException {
				if (event.getType().equals(FSHooks.SVN_REPOS_HOOK_POST_COMMIT)) {
					String name = event.getReposRootDir().getName();
					invalidateCache(name);
				}
			}
		});
		svnClientManager = fileEntityRepository.getSVNClientManager();
		fileEntryCache = cacheManager.getCache("file_entries");

	}

	/**
	 * invalidate the file_entry_search_cache.
	 *
	 * @param userId userId.
	 */
	public void invalidateCache(String userId) {
		fileEntryCache.evict(userId);
	}

	/**
	 * Create user svn repo.
	 * <p>
	 * This method is executed async way.
	 *
	 * @param user newly created user.
	 */
	@Async
	public void prepare(User user) {
		File newUserDirectory = getUserRepoDirectory(user);
		try {
			if (!newUserDirectory.exists()) {
				createUserRepo(user, newUserDirectory);
			}
		} catch (SVNException e) {
			LOG.error("Error while prepare user {}'s repo", user.getUserName(), e);
		}
	}

	private SVNURL createUserRepo(User user, File newUserDirectory) throws SVNException {
		return svnClientManager.getAdminClient().doCreateRepository(newUserDirectory, user.getUserId(), true, true);
	}

	private File getUserRepoDirectory(User user) {
		return new File(config.getHome().getRepoDirectoryRoot(), checkNotNull(user.getUserId()));
	}

	/**
	 * Get all {@link FileEntry} for the given user. This method is subject to
	 * be cached because it takes time.
	 *
	 * @param user user
	 * @return cached {@link FileEntry} list
	 */
	@Cacheable(value = "file_entries", key = "#user.userId")
	public List<FileEntry> getAll(User user) {
		prepare(user);
		List<FileEntry> allFileEntries;
		try {
			allFileEntries = fileEntityRepository.findAll(user);
		} catch (Exception e) {
			// Try once more for the case of the underlying file system fault.
			ThreadUtils.sleep(3000);
			allFileEntries = fileEntityRepository.findAll(user);
		}
		return unmodifiableList(allFileEntries);
	}

	/**
	 * Get file entries from underlying svn for given path.
	 *
	 * @param user     the user
	 * @param path     path in the repo
	 * @param revision revision number. -1 if HEAD.
	 * @return file entry list
	 */
	public List<FileEntry> getAll(User user, String path, Long revision) {
		// If it's not created, make one.
		prepare(user);
		return fileEntityRepository.findAll(user, path, revision);
	}

	/**
	 * Get file entity for the given revision.
	 *
	 * @param user     the user
	 * @param path     path in the repo
	 * @param revision revision. if -1, HEAD
	 * @return file entity
	 */
	public FileEntry getOne(User user, String path, Long revision) {
		SVNRevision svnRev = (revision == null || revision == -1) ? SVNRevision.HEAD : SVNRevision.create(revision);
		return fileEntityRepository.findOne(user, path, svnRev);
	}

	/**
	 * Get single file entity.
	 * <p>
	 * The return value has content byte.
	 *
	 * @param user the user
	 * @param path path in the svn repo
	 * @return single file entity
	 */
	public FileEntry getOne(User user, String path) {
		return getOne(user, path, -1L);
	}

	/**
	 * Check file existence.
	 *
	 * @param user user
	 * @param path path in user repo
	 * @return true if exists.
	 */
	public boolean hasFileEntry(User user, String path) {
		return fileEntityRepository.hasOne(user, path);
	}

	/**
	 * Add folder on the given path.
	 *
	 * @param user       user
	 * @param path       base path
	 * @param comment    comment
	 * @param folderName folder name
	 */
	public void addFolder(User user, String path, String folderName, String comment) {
		FileEntry entry = new FileEntry();
		entry.setPath(PathUtils.join(path, folderName));
		entry.setFileType(FileType.DIR);
		entry.setDescription(comment);
		fileEntityRepository.save(user, entry, null);
	}


	/**
	 * Save File entry.
	 *
	 * @param user      the user
	 * @param fileEntry fileEntry to be saved
	 */
	public void save(User user, FileEntry fileEntry) {
		prepare(user);
		checkNotEmpty(fileEntry.getPath());
		fileEntityRepository.save(user, fileEntry, fileEntry.getEncoding());
	}

	/**
	 * Delete file entries.
	 *
	 * @param user     the user
	 * @param basePath the base path
	 * @param files    files under base path
	 */
	public void delete(User user, String basePath, String[] files) {
		List<String> fullPathFiles = new ArrayList<String>();
		for (String each : files) {
			fullPathFiles.add(basePath + "/" + each);
		}
		fileEntityRepository.delete(user, fullPathFiles);
	}

	/**
	 * Delete file entry.
	 *
	 * @param user the user
	 * @param path the path
	 */
	public void delete(User user, String path) {
		fileEntityRepository.delete(user, newArrayList(path));
	}

	String getPathFromUrl(String urlString) {
		try {
			URL url = new URL(urlString);
			String urlPath = "/".equals(url.getPath()) ? "" : url.getPath();
			return (url.getHost() + urlPath).replaceAll("[;\\&\\?\\%\\$\\-\\#]", "_");
		} catch (MalformedURLException e) {
			throw processException("Error while translating " + urlString, e);
		}
	}

	String[] dividePathAndFile(String path) {
		int lastIndexOf = path.lastIndexOf("/");
		if (lastIndexOf == -1) {
			return new String[]{"", path};
		} else {
			return new String[]{path.substring(0, lastIndexOf), path.substring(lastIndexOf + 1)};
		}
	}

	/**
	 * Create new FileEntry.
	 *
	 * @param user           user
	 * @param path           base path path
	 * @param fileName       fileName
	 * @param name           name
	 * @param url            url
	 * @param scriptHandler  script handler
	 * @param libAndResource true if lib and resources should be created
	 * @return created file entry. main test file if it's the project creation.
	 */
	public FileEntry prepareNewEntry(User user, String path, String fileName, String name, String url,
	                                 ScriptHandler scriptHandler, boolean libAndResource, String options) {
		if (scriptHandler instanceof ProjectHandler) {
			scriptHandler.prepareScriptEnv(user, path, fileName, name, url, libAndResource,
				loadTemplate(user, getScriptHandler("groovy"), url, name, options));
			return null;
		}
		path = PathUtils.join(path, fileName);
		FileEntry fileEntry = new FileEntry();
		fileEntry.setPath(path);
		fileEntry.setContent(loadTemplate(user, scriptHandler, url, name, options));
		if (!"http://please_modify_this.com".equals(url)) {
			fileEntry.setProperties(buildMap("targetHosts", UrlUtils.getHost(url)));
		} else {
			fileEntry.setProperties(new HashMap<String, String>());
		}
		return fileEntry;
	}

	/**
	 * Create new FileEntry for the given URL.
	 *
	 * @param user          user
	 * @param url           URL to be tested.
	 * @param scriptHandler scriptHandler
	 * @return created new {@link FileEntry}
	 */
	public FileEntry prepareNewEntryForQuickTest(User user, String url,
	                                             ScriptHandler scriptHandler) {
		String path = getPathFromUrl(url);
		String host = UrlUtils.getHost(url);
		FileEntry quickTestFile = scriptHandler.getDefaultQuickTestFilePath(path);
		String nullOptions = null;
		if (scriptHandler instanceof ProjectHandler) {
			String[] pathPart = dividePathAndFile(path);
			prepareNewEntry(user, pathPart[0], pathPart[1], host, url, scriptHandler, false, nullOptions);
		} else {
			FileEntry fileEntry = prepareNewEntry(user, path, quickTestFile.getFileName(), host, url, scriptHandler,
				false, nullOptions);
			fileEntry.setDescription("Quick test for " + url);
			save(user, fileEntry);
		}
		return quickTestFile;
	}

	/**
	 * Load freemarker template for quick test.
	 *
	 * @param user    user
	 * @param handler handler
	 * @param url     url
	 * @param name    name
	 * @return generated test script
	 */
	public String loadTemplate(User user, ScriptHandler handler, String url, String name,
	                           String options) {
		Map<String, Object> map = newHashMap();
		map.put("url", url);
		map.put("userName", user.getUserName());
		map.put("name", name);
		map.put("options", options);
		return handler.getScriptTemplate(map);
	}

	/**
	 * Get a SVN content into given dir.
	 *
	 * @param user     user
	 * @param fromPath path in svn subPath
	 * @param toDir    to directory
	 */
	public void writeContentTo(User user, String fromPath, File toDir) {
		fileEntityRepository.writeContentTo(user, fromPath, toDir);
	}

	/**
	 * Get the appropriate {@link ScriptHandler} subclass for the given
	 * {@link FileEntry}.
	 *
	 * @param scriptEntry script entry
	 * @return scriptHandler
	 */
	public ScriptHandler getScriptHandler(FileEntry scriptEntry) {
		return scriptHandlerFactory.getHandler(scriptEntry);
	}

	/**
	 * Get the appropriate {@link ScriptHandler} subclass for the given
	 * ScriptHandler key.
	 *
	 * @param key script entry
	 * @return scriptHandler
	 */
	public ScriptHandler getScriptHandler(String key) {
		return scriptHandlerFactory.getHandler(key);
	}

	/**
	 * ignore Headers.
	 *
	 * @param headers headers
	 * @param ignores ignores
	 * @return entrys
	 */
	private Map<String, String> ignoreHeaders(Map<String, String> headers, String[] ignores) {
		for (String ignore : ignores) {
			headers.remove(ignore);
		}
		return headers;
	}

	/**
	 * Load HAR and return converted script.
	 *
	 * @param har HAR content
	 * @return resultMap converted script map
	 * @throws JSONException, IOException
	 */
	public Map<String, Object> convertToScript(String har, boolean removeStaticResource) throws JSONException, IOException {
		Map<String, Object> resultMap = newHashMap();
		Map<String, Object> paramMap = newHashMap();
		paramMap = getHARParam(har, removeStaticResource);
		resultMap.put("groovy", getScriptHandler("groovy").getScriptTemplate(paramMap));
		resultMap.put("jython", getScriptHandler("jython").getScriptTemplate(paramMap));
		return resultMap;
	}

	/**
	 * Clean Static Resources.
	 *
	 * @param har HAR
	 * @return cleaned HAR
	 */
	HAR cleanStaticResources(HAR har) {
		HAR result = new HAR();
		ArrayList<HAR.HAREntry> harEntrys = new ArrayList<HAR.HAREntry>();
		for (HAR.HAREntry harEntry : har.getLog().getEntries()) {
			boolean recordable = false;
			for (HAR.Header header : harEntry.getResponse().getHeaders()) {
				if ("Content-Type".equals(header.getName())) {
					if (StringUtils.startsWith(header.getValue(), "text") ||
						StringUtils.startsWith(header.getValue(), "application")) {
						harEntrys.add(harEntry);
						break;
					}
				}
			}
		}
		result.setLog(har.getLog());
		result.getLog().setEntries(harEntrys);
		return result;
	}

	/**
	 * load HAR File.
	 *
	 * @param file HAR file
	 * @param removeStaticResource true if you want to remove static resources from HAR
	 * @return String cleaned HAR
	 * @throws IOException
	 */
	public String loadHAR(MultipartFile file, boolean removeStaticResource) throws IOException {
		HAR har = new HAR();
		InputStream io = null;
		ObjectMapper mapper = new ObjectMapper();
		try {
			io = file.getInputStream();
			har = mapper.readValue(io, HAR.class);
			if (removeStaticResource) {
				har = cleanStaticResources(har);
			}
		} catch (IOException e) {
			throw processException(e);
		} finally {
			IOUtils.closeQuietly(io);
		}
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(har);
	}

	/**
	 * Get HAR param.
	 *
	 * @param paramHar HAR Content
	 * @param removeStaticResource true if you want to remove static resources from HAR
	 * @return paramMap result Map
	 * @throws JSONException, IOException
	 */
	Map<String, Object> getHARParam(String paramHar, boolean removeStaticResource) throws JSONException, IOException {
		HAR har;
		String[] ignores = {"Host"};
		Map<String, Object> paramMap = newHashMap();
		ArrayList<Request> requests = new ArrayList<Request>();
		ObjectMapper mapper = new ObjectMapper();
		try {
			har = mapper.readValue(paramHar, HAR.class);
		} catch (IOException e) {
			throw processException(e);
		}

		if (removeStaticResource) {
			har = cleanStaticResources(har);
		}

		// Extract commonHeaders.
		Map<String, String> commonHeader = ignoreHeaders(extractCommonHeader(har), ignores);

		// Set requests.
		for (HAR.HAREntry harEntry : har.getLog().getEntries()) {
			Request request = new Request();
			Map<String, String> tempHeader = newHashMap();
			Map<String, String> postData = newHashMap();

			for (HAR.Header header :harEntry.getRequest().getHeaders()) {
				tempHeader.put(header.getName(), header.getValue());
			}

			if(harEntry.getRequest().getPostData() != null && harEntry.getRequest().getPostData().getParams() != null) {
				for (HAR.param param : harEntry.getRequest().getPostData().getParams()) {
					postData.put(param.getName(), param.getValue());
				}
				request.setPostData(postData);
			}
			request.setMethod(harEntry.getRequest().getMethod());
			request.setUrl(harEntry.getRequest().getUrl());
			request.setState(harEntry.getResponse().getStatus());
			request.setHeaders(ignoreHeaders(tempHeader, ignores));
			requests.add(request);
		}
		paramMap.put("requests", requests);
		paramMap.put("commonHeader", commonHeader);
		return paramMap;
	}

	/**
	 * Extract Common Header.
	 *
	 * @param har HAR
	 * @return commonHeaders common headers
	 */
	private Map<String, String> extractCommonHeader(HAR har) {
		Map<String, String> commonHeaders = newHashMap();
		boolean first = true;
		for (HAR.HAREntry harEntry : har.getLog().getEntries()) {
			Map<String, String> headers = newHashMap();
			for (HAR.Header header : harEntry.getRequest().getHeaders()) {
				headers.put(header.getName(), header.getValue());
			}
			if (first) {
				commonHeaders = headers;
				first = false;
			} else {
				commonHeaders.entrySet().retainAll(headers.entrySet());
			}
		}
		return commonHeaders;
	}

}
