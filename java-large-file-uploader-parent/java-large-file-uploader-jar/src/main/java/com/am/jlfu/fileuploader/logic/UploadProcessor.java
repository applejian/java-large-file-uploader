package com.am.jlfu.fileuploader.logic;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

import com.am.jlfu.fileuploader.exception.InvalidCrcException;
import com.am.jlfu.fileuploader.json.CRCResult;
import com.am.jlfu.fileuploader.json.FileStateJson;
import com.am.jlfu.fileuploader.json.FileStateJsonBase;
import com.am.jlfu.fileuploader.json.InitializationConfiguration;
import com.am.jlfu.fileuploader.json.PrepareUploadJson;
import com.am.jlfu.fileuploader.limiter.RateLimiterConfigurationManager;
import com.am.jlfu.fileuploader.limiter.RequestUploadProcessingConfiguration;
import com.am.jlfu.fileuploader.utils.CRCHelper;
import com.am.jlfu.fileuploader.utils.ConditionProvider;
import com.am.jlfu.fileuploader.utils.GeneralUtils;
import com.am.jlfu.notifier.JLFUListenerPropagator;
import com.am.jlfu.staticstate.StaticStateDirectoryManager;
import com.am.jlfu.staticstate.StaticStateIdentifierManager;
import com.am.jlfu.staticstate.StaticStateManager;
import com.am.jlfu.staticstate.StaticStateManagerService;
import com.am.jlfu.staticstate.entities.StaticFileState;
import com.am.jlfu.staticstate.entities.StaticStatePersistedOnFileSystemEntity;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Maps.EntryTransformer;
import com.google.common.collect.Ordering;



@Component
@ManagedResource(objectName = "JavaLargeFileUploader:name=uploadServletProcessor")
public class UploadProcessor {

	private static final Logger log = LoggerFactory.getLogger(UploadProcessor.class);

	@Autowired
	CRCHelper crcHelper;

	@Autowired
	RateLimiterConfigurationManager uploadProcessingConfigurationManager;

	@Autowired
	StaticStateManager<StaticStatePersistedOnFileSystemEntity> staticStateManager;

	@Autowired
	StaticStateManagerService<StaticStatePersistedOnFileSystemEntity> staticStateManagerService;

	@Autowired
	StaticStateIdentifierManager staticStateIdentifierManager;

	@Autowired
	StaticStateDirectoryManager staticStateDirectoryManager;

	@Autowired
	GeneralUtils generalUtils;

	@Autowired
	private JLFUListenerPropagator jlfuListenerPropagator;

	public static final int SIZE_OF_FIRST_CHUNK_VALIDATION = 8192;

	/**
	 * Size of a slice <br>
	 * Default to 10MB.
	 */
	@Value("${jlfu.sliceSizeInBytes:10485760}")
	private long sliceSizeInBytes;



	public InitializationConfiguration getConfig(UUID clientId) {

		// specify the client id
		if (clientId != null) {
			staticStateIdentifierManager.setIdentifier(clientId);
		}

		// prepare
		InitializationConfiguration config = new InitializationConfiguration();
		StaticStatePersistedOnFileSystemEntity entity = staticStateManager.getEntity();

		if (entity != null) {

			// order files by age
			Ordering<UUID> ordering = Ordering.from(new Comparator<StaticFileState>() {

				@Override
				public int compare(StaticFileState o1, StaticFileState o2) {
					int compareTo = o1.getStaticFileStateJson().getCreationDate().compareTo(o2.getStaticFileStateJson().getCreationDate());
					return compareTo != 0 ? compareTo : 1;
				}

			}).onResultOf(Functions.forMap(entity.getFileStates()));

			// apply comparator
			ImmutableSortedMap<UUID, StaticFileState> sortedMap = ImmutableSortedMap.copyOf(entity.getFileStates(), ordering);

			// fill pending files from static state
			final SortedMap<UUID, FileStateJson> transformEntries =
					Maps.transformEntries(sortedMap, new EntryTransformer<UUID, StaticFileState, FileStateJson>() {

						@Override
						public FileStateJson transformEntry(UUID fileId, StaticFileState value) {

							// wait until pending processing is performed
							waitUntilProcessingAreFinished(fileId);

							return getFileStateJson(fileId, value);
						}
					});

			// change keys
			Map<String, FileStateJson> newMap = Maps.newHashMap();
			for (Entry<UUID, FileStateJson> entry : transformEntries.entrySet()) {
				newMap.put(entry.getKey().toString(), entry.getValue());
			}

			// apply transformed map
			config.setPendingFiles(newMap);

			// reset paused attribute
			for (UUID fileId : sortedMap.keySet()) {
				uploadProcessingConfigurationManager.getUploadProcessingConfiguration(fileId).setPaused(false);
			}
		}

		// fill configuration
		config.setInByte(sliceSizeInBytes);

		return config;
	}


	private void waitUntilProcessingAreFinished(UUID fileId) {
		final RequestUploadProcessingConfiguration uploadProcessingConfiguration =
				uploadProcessingConfigurationManager.getUploadProcessingConfiguration(fileId);
		generalUtils.waitForConditionToCompleteRunnable(5000, new ConditionProvider() {

			@Override
			public boolean condition() {
				return !uploadProcessingConfiguration.isProcessing();
			}


			public void onFail() {
				log.error("/!\\ One of the pending file upload is still processing !!");
			};
		});
	}


	private FileStateJson getFileStateJson(UUID fileId, StaticFileState value) {

		// process
		File file = new File(value.getAbsoluteFullPathOfUploadedFile());
		Long fileSize = file.length();

		FileStateJsonBase staticFileStateJson = value.getStaticFileStateJson();
		FileStateJson fileStateJson = new FileStateJson();
		fileStateJson.setFileComplete(staticFileStateJson.getCrcedBytes().equals(staticFileStateJson.getOriginalFileSizeInBytes()));
		fileStateJson.setFileCompletionInBytes(fileSize);
		fileStateJson.setOriginalFileName(staticFileStateJson.getOriginalFileName());
		fileStateJson.setOriginalFileSizeInBytes(staticFileStateJson.getOriginalFileSizeInBytes());
		fileStateJson.setRateInKiloBytes(staticFileStateJson.getRateInKiloBytes());
		fileStateJson.setCrcedBytes(staticFileStateJson.getCrcedBytes());
		fileStateJson.setFirstChunkCrc(staticFileStateJson.getFirstChunkCrc());
		fileStateJson.setCreationDate(staticFileStateJson.getCreationDate());
		log.debug("returning pending file " + fileStateJson.getOriginalFileName() + " with target size " +
				fileStateJson.getOriginalFileSizeInBytes() + " out of " + fileSize + " completed which includes " +
				fileStateJson.getCrcedBytes() + " bytes validated and " + (fileSize - fileStateJson.getCrcedBytes()) + " unvalidated.");

		return fileStateJson;
	}


	public UUID prepareUpload(Long size, String fileName, String crc)
			throws IOException {

		// retrieve model
		StaticStatePersistedOnFileSystemEntity model = staticStateManager.getEntity();

		// extract the extension of the filename
		String fileExtension = extractExtensionOfFileName(fileName);

		// create a new file for it
		UUID fileId = UUID.randomUUID();
		File file = new File(staticStateDirectoryManager.getUUIDFileParent(), fileId + fileExtension);
		file.createNewFile();
		StaticFileState fileState = new StaticFileState();
		FileStateJsonBase jsonFileState = new FileStateJsonBase();
		fileState.setStaticFileStateJson(jsonFileState);
		fileState.setAbsoluteFullPathOfUploadedFile(file.getAbsolutePath());
		model.getFileStates().put(fileId, fileState);

		// add info to the state
		jsonFileState.setOriginalFileName(fileName);
		jsonFileState.setOriginalFileSizeInBytes(size);
		jsonFileState.setFirstChunkCrc(crc);
		jsonFileState.setCreationDate(new Date());

		// write the state
		staticStateManager.updateEntity(model);

		// call listener
		jlfuListenerPropagator.getPropagator().onFileUploadPrepared(staticStateIdentifierManager.getIdentifier(), fileId);

		// and returns the file identifier
		log.debug("File prepared for client " + staticStateIdentifierManager.getIdentifier() + " at path " + file.getAbsolutePath());
		return fileId;

	}


	public HashMap<String, UUID> prepareUpload(PrepareUploadJson[] fromJson)
			throws IOException {
		HashMap<String, UUID> returnMap = Maps.newHashMap();

		// for all of them
		for (PrepareUploadJson prepareUploadJson : fromJson) {

			// prepare it
			UUID idOfTheFile =
					prepareUpload(prepareUploadJson.getSize(), prepareUploadJson.getFileName(), prepareUploadJson.getCrc());

			// put in map
			returnMap.put(prepareUploadJson.getTempId().toString(), idOfTheFile);

			// notify listener
			jlfuListenerPropagator.getPropagator().onFileUploadPrepared(staticStateIdentifierManager.getIdentifier(), idOfTheFile);

		}

		// notify that all are processed
		jlfuListenerPropagator.getPropagator().onAllFileUploadsPrepared(staticStateIdentifierManager.getIdentifier(), returnMap.values());

		return returnMap;
	}


	private String extractExtensionOfFileName(String fileName) {
		String[] split = fileName.split("\\.");
		String fileExtension = "";
		if (split.length > 1) {
			if (split.length > 0) {
				fileExtension = '.' + split[split.length - 1];
			}
		}
		return fileExtension;
	}


	private void closeStreamForFiles(UUID... fileIds) {

		// submit them
		List<ConditionProvider> conditionProviders = Lists.newArrayList();
		for (final UUID fileId : fileIds) {

			log.debug("asking for stream close for file with id " + fileId);

			// ask for the stream to close
			boolean needToCloseStream = uploadProcessingConfigurationManager.markRequestHasShallBeCancelled(fileId);

			if (needToCloseStream) {
				log.debug("waiting for the stream to be closed for " + fileId);

				conditionProviders.add(new ConditionProvider() {

					@Override
					public boolean condition() {
						return !uploadProcessingConfigurationManager.requestIsReset(fileId);
					}


					@Override
					public void onSuccess() {
						log.debug("file stream has been closed for " + fileId);
					}


					@Override
					public void onFail() {
						log.warn("cannot confirm that file stream is closed for " + fileId);
					}

				});

			}
		}

		generalUtils.waitForConditionToCompleteRunnable(1000, conditionProviders);

	}


	public void clearFile(UUID fileId)
			throws InterruptedException, ExecutionException, TimeoutException {

		// ask for the stream to be closed
		closeStreamForFiles(fileId);

		// then delete
		staticStateManager.clearFile(fileId);

		// then call listener
		jlfuListenerPropagator.getPropagator().onFileUploadCancelled(staticStateIdentifierManager.getIdentifier(), fileId);
	}


	public void clearAll()
			throws InterruptedException, ExecutionException, TimeoutException {

		// close any pending stream before clearing the file
		closeStreamForFiles(staticStateManager.getEntity().getFileStates().keySet().toArray(new UUID[] {}));


		// then clear everything
		staticStateManager.clear();
	}


	public Float getProgress(UUID fileId)
			throws FileNotFoundException {

		// get the identifier of the client
		UUID clientId = staticStateIdentifierManager.getIdentifier();

		// return progress
		return staticStateManagerService.getProgress(clientId, fileId);
	}


	public Long getUploadStat(UUID fileId) {
		return uploadProcessingConfigurationManager.getUploadState(fileId);
	}


	public void setUploadRate(UUID fileId, Long rate) {

		// set the rate
		uploadProcessingConfigurationManager.assignRateToRequest(fileId, rate);

		// save it for the file with this file id
		StaticStatePersistedOnFileSystemEntity entity = staticStateManager.getEntity();
		entity.getFileStates().get(fileId).getStaticFileStateJson().setRateInKiloBytes(rate);

		// persist changes
		staticStateManager.updateEntity(entity);
	}


	public void pauseFile(List<UUID> uuids) {

		// for all these files
		for (UUID uuid : uuids) {

			// specify as paused
			uploadProcessingConfigurationManager.pause(uuid);

			// close stream
			closeStreamForFiles(uuid);

			// then call listener
			jlfuListenerPropagator.getPropagator().onFileUploadPaused(staticStateIdentifierManager.getIdentifier(), uuid);
		}

	}


	public FileStateJson resumeFile(UUID fileId) {

		// set as resumed
		uploadProcessingConfigurationManager.resume(fileId);

		// then call listener
		jlfuListenerPropagator.getPropagator().onFileUploadResumed(staticStateIdentifierManager.getIdentifier(), fileId);

		// and return some information about it
		return getFileStateJson(fileId, staticStateManager.getEntity().getFileStates().get(fileId));
	}


	public void verifyCrcOfUncheckedPart(UUID fileId, String inputCrc)
			throws IOException, InvalidCrcException {
		log.debug("validating the bytes that have not been validated from the previous interrupted upload for file " +
				fileId);

		// get entity
		StaticStatePersistedOnFileSystemEntity model = staticStateManager.getEntity();

		// get the file
		StaticFileState fileState = model.getFileStates().get(fileId);
		if (fileState == null) {
			throw new FileNotFoundException("File with id " + fileId + " not found");
		}
		File file = new File(fileState.getAbsoluteFullPathOfUploadedFile());


		// if the file does not exist, there is an issue!
		if (!file.exists()) {
			throw new FileNotFoundException("File with id " + fileId + " not found");
		}

		// open the file stream
		FileInputStream fileInputStream = new FileInputStream(file);
		// skip the crced part
		fileInputStream.skip(fileState.getStaticFileStateJson().getCrcedBytes());

		// read the crc
		final CRCResult fileCrc = crcHelper.getBufferedCrc(fileInputStream);
		IOUtils.closeQuietly(fileInputStream);

		// compare them
		log.debug("validating chunk crc " + fileCrc.getCrcAsString() + " against " + inputCrc);

		// if not equal, we have an issue:
		if (!fileCrc.getCrcAsString().equals(inputCrc)) {
			log.debug("invalid crc ... now truncating file to match validated bytes " + fileState.getStaticFileStateJson().getCrcedBytes());

			// we are just sure now that the file before the crc validated is actually valid, and
			// after that, it seems it is not
			// so we get the file and remove everything after that crc validation so that user can
			// resume the fileupload from there.

			// truncate the file
			RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rwd");
			randomAccessFile.setLength(fileState.getStaticFileStateJson().getCrcedBytes());
			randomAccessFile.close();

			// throw the exception
			throw new InvalidCrcException(fileCrc.getCrcAsString(), inputCrc);
		}
		// if correct, we can add these bytes as validated inside the file
		else {
			staticStateManager.setCrcBytesValidated(staticStateIdentifierManager.getIdentifier(), fileId,
					fileCrc.getTotalRead());
		}

	}


	private void showDif(byte[] a, byte[] b) {

		log.debug("comparing " + a + " to " + b);
		log.debug("size: " + a.length + " " + b.length);
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			if (!Byte.valueOf(a[i]).equals(Byte.valueOf(b[i]))) {
				log.debug("different byte at index " + i + " : " + Byte.valueOf(a[i]) + " " + Byte.valueOf(b[i]));
			}
		}
		if (a.length != b.length) {
			log.debug("arrays do not have a similar size so i was impossible to compare " + Math.abs(a.length - b.length) + " bytes.");
		}

	}


	@ManagedAttribute
	public long getSliceSizeInBytes() {
		return sliceSizeInBytes;
	}


	@ManagedAttribute
	public void setSliceSizeInBytes(long sliceSizeInBytes) {
		this.sliceSizeInBytes = sliceSizeInBytes;
	}


}
