/**
 * DSS - Digital Signature Services
 * Copyright (C) 2015 European Commission, provided under the CEF programme
 * 
 * This file is part of the "DSS - Digital Signature Services" project.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package eu.europa.esig.dss.tsl.runnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.client.http.DSSFileLoader;
import eu.europa.esig.dss.spi.x509.CertificateSource;
import eu.europa.esig.dss.tsl.cache.CacheKey;
import eu.europa.esig.dss.tsl.cache.access.CacheAccessByKey;
import eu.europa.esig.dss.tsl.cache.access.CacheAccessFactory;
import eu.europa.esig.dss.tsl.cache.access.ReadOnlyCacheAccess;
import eu.europa.esig.dss.tsl.dto.ParsingCacheDTO;
import eu.europa.esig.dss.tsl.dto.ValidationCacheDTO;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import eu.europa.esig.dss.tsl.validation.TLValidatorTask;
import eu.europa.esig.dss.utils.Utils;

/**
 * Runs the job for a LOTL with pivots analysis
 */
public class LOTLWithPivotsAnalysis extends AbstractRunnableAnalysis implements Runnable {


	/** Loads a relevant cache access object */
	private final CacheAccessFactory cacheAccessFactory;

	/** The LOTL source */
	private final LOTLSource lotlSource;

	/** The file loader */
	private final DSSFileLoader dssFileLoader;



	/**
	 * Default constructor
	 *
	 * @param cacheAccessFactory {@link CacheAccessFactory}
	 * @param source {@link LOTLSource}
	 * @param dssFileLoader {@link DSSFileLoader}
	 * @param latch {@link CountDownLatch}
	 */
	public LOTLWithPivotsAnalysis(final CacheAccessFactory cacheAccessFactory, final LOTLSource source,
			final DSSFileLoader dssFileLoader, final CountDownLatch latch) {
		super(cacheAccessFactory.getCacheAccess(source.getCacheKey()), dssFileLoader, latch);
		this.cacheAccessFactory = cacheAccessFactory;
		this.lotlSource = source;
		this.dssFileLoader = dssFileLoader;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void doAnalyze() {
		DSSDocument document = download(lotlSource.getUrl());

		if (document != null) {
			lotlParsing(document, lotlSource);

			validation(document, getCurrentCertificateSource());
		}
	}

	private CertificateSource getCurrentCertificateSource() {
		final CertificateSource initialCertificateSource = lotlSource.getCertificateSource();

		CertificateSource currentCertificateSource;

		ParsingCacheDTO currentLOTLParsing = getCacheAccessByKey().getParsingReadOnlyResult();
		if (currentLOTLParsing != null) {
			List<String> pivotURLs = currentLOTLParsing.getPivotUrls();
			if (Utils.isCollectionEmpty(pivotURLs)) {
				logger.trace("No pivot LOTL found");
				currentCertificateSource = initialCertificateSource;
			} else {
				currentCertificateSource = getCurrentCertificateSourceFromPivots(initialCertificateSource, pivotURLs);
			}
		} else {
			logger.warn("Unable to retrieve the parsing result for the current LOTL (allowed signing certificates set from the configuration)");
			currentCertificateSource = initialCertificateSource;
		}

		return currentCertificateSource;
	}

	private CertificateSource getCurrentCertificateSourceFromPivots(CertificateSource initialCertificateSource, List<String> pivotURLs) {

		/*-
		* current 																						-> Signed with pivot 226 certificates
		* https://ec.europa.eu/information_society/policy/esignature/trusted-list/tl-pivot-226-mp.xml	-> Signed with pivot 191 certificates
		* https://ec.europa.eu/information_society/policy/esignature/trusted-list/tl-pivot-191-mp.xml	-> Signed with pivot 172 certificates
		* https://ec.europa.eu/information_society/policy/esignature/trusted-list/tl-pivot-172-mp.xml 	-> Signed with OJ Certs
		* http://eur-lex.europa.eu/legal-content/EN/TXT/?uri=uriserv:OJ.C_.2016.233.01.0001.01.ENG		-> OJ
		*/

		Map<String, PivotProcessingResult> processingResults = downloadAndParseAllPivots(pivotURLs);

		ReadOnlyCacheAccess readOnlyCacheAccess = cacheAccessFactory.getReadOnlyCacheAccess();

		List<String> pivotUrlsReversed = Utils.reverseList(pivotURLs); // -> 172, 191,..

		CertificateSource currentCertificateSource = initialCertificateSource;
		for (String pivotUrl : pivotUrlsReversed) {
			CacheKey cacheKey = new CacheKey(pivotUrl);

			PivotProcessingResult pivotProcessingResult = processingResults.get(pivotUrl);
			if (pivotProcessingResult != null) {
				CacheAccessByKey pivotCacheAccess = cacheAccessFactory.getCacheAccess(cacheKey);
				validationPivot(pivotCacheAccess, pivotProcessingResult.getPivot(), currentCertificateSource);

				ValidationCacheDTO validationResult = readOnlyCacheAccess.getValidationCacheDTO(cacheKey);
				if (validationResult != null) {
					if (validationResult.isValid()) {
						currentCertificateSource = pivotProcessingResult.getCertificateSource();
					} else {
						logger.warn("Pivot LOTL '{}' is not valid ({}/{})", pivotUrl, validationResult.getIndication(), validationResult.getSubIndication());
					}
				} else {
					logger.warn("No validation result found for Pivot LOTL '{}'", pivotUrl);
				}
			} else {
				logger.warn("No processing result for Pivot LOTL '{}'", pivotUrl);
			}
		}

		return currentCertificateSource;
	}

	private void validationPivot(CacheAccessByKey pivotCacheAccess, DSSDocument document, CertificateSource certificateSource) {
		// True if EMPTY / EXPIRED by TL/LOTL
		if (pivotCacheAccess.isValidationRefreshNeeded()) {
			try {
				logger.debug("Validating the Pivot LOTL with cache key '{}'...", pivotCacheAccess.getCacheKey().getKey());
				TLValidatorTask validationTask = new TLValidatorTask(document, certificateSource);
				pivotCacheAccess.update(validationTask.get());
			} catch (Exception e) {
				logger.error("Cannot validate the Pivot LOTL with the cache key '{}' : {}", pivotCacheAccess.getCacheKey().getKey(), e.getMessage());
				pivotCacheAccess.validationError(e);
			}
		}
	}

	private Map<String, PivotProcessingResult> downloadAndParseAllPivots(List<String> pivotURLs) {
		ExecutorService executorService = Executors.newFixedThreadPool(pivotURLs.size());

		Map<String, Future<PivotProcessingResult>> futures = new HashMap<>();
		for (String pivotUrl : pivotURLs) {
			CacheAccessByKey pivotCacheAccess = cacheAccessFactory.getCacheAccess(new CacheKey(pivotUrl));
			LOTLSource pivotSource = new LOTLSource();
			pivotSource.setUrl(pivotUrl);
			pivotSource.setLotlPredicate(lotlSource.getLotlPredicate());
			pivotSource.setTlPredicate(lotlSource.getTlPredicate());
			pivotSource.setPivotSupport(lotlSource.isPivotSupport());
			futures.put(pivotUrl, executorService.submit((Callable<PivotProcessingResult>)new PivotProcessing(pivotSource, pivotCacheAccess, dssFileLoader)));
		}

		Map<String, PivotProcessingResult> processingResults = new HashMap<>();
		for (Entry<String, Future<PivotProcessingResult>> entry : futures.entrySet()) {
			try {
				processingResults.put(entry.getKey(), entry.getValue().get());
			} catch (InterruptedException e) {
				logger.error(String.format("Unable to retrieve the PivotProcessingResult for url '%s'", entry.getKey()), e);
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				logger.error(String.format("Unable to retrieve the PivotProcessingResult for url '%s'", entry.getKey()), e);
			}
		}

		shutdownAndAwaitTermination(executorService);

		return processingResults;
	}

	private void shutdownAndAwaitTermination(ExecutorService executorService) {
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
				shutdownNowAndAwaitTermination(executorService);
			}
		} catch (InterruptedException e) {
			shutdownNowAndAwaitTermination(executorService);
			Thread.currentThread().interrupt();
		}
	}

	private void shutdownNowAndAwaitTermination(ExecutorService executorService) {
		executorService.shutdownNow();
		try {
			if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
				logger.warn("More than 10s to terminate the service executor");
			}
		} catch (InterruptedException e) {
			logger.warn("Unable to interrupt the service executor", e);
			Thread.currentThread().interrupt();
		}
	}
}
