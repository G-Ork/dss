package eu.europa.esig.dss.jades.validation;

import java.util.Map;

import org.bouncycastle.asn1.x509.IssuerSerial;
import org.jose4j.jwx.HeaderParameterNames;

import eu.europa.esig.dss.jades.JAdESHeaderParameterNames;
import eu.europa.esig.dss.jades.JAdESUtils;
import eu.europa.esig.dss.model.Digest;
import eu.europa.esig.dss.spi.DSSASN1Utils;
import eu.europa.esig.dss.spi.x509.CertificateRef;

public final class JAdESCertificateRefExtractionUtils {

	private JAdESCertificateRefExtractionUtils() {
	}

	public static CertificateRef createCertificateRef(Map<?, ?> certificateRefMap) {
		IssuerSerial issuerSerial = JAdESUtils.getIssuerSerial((String) certificateRefMap.get(HeaderParameterNames.KEY_ID));

		Map<?, ?> digAlgoVal = (Map<?, ?>) certificateRefMap.get(JAdESHeaderParameterNames.DIG_ALG_VAL);
		Digest digest = JAdESUtils.getDigest(digAlgoVal);
		if (digest != null) {
			CertificateRef certificateRef = new CertificateRef();
			certificateRef.setCertDigest(digest);
			if (issuerSerial != null) {
				certificateRef.setCertificateIdentifier(DSSASN1Utils.toCertificateIdentifier(issuerSerial));
			}
			return certificateRef;
		}

		return null;
	}

}