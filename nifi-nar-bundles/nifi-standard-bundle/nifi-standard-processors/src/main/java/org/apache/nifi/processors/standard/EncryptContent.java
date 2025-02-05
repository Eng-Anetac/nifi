/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.SystemResource;
import org.apache.nifi.annotation.behavior.SystemResourceConsideration;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.DeprecationNotice;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.deprecation.log.DeprecationLogger;
import org.apache.nifi.deprecation.log.DeprecationLoggerFactory;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.security.util.EncryptionMethod;
import org.apache.nifi.security.util.KeyDerivationFunction;
import org.apache.nifi.security.util.crypto.CipherUtility;
import org.apache.nifi.security.util.crypto.KeyedEncryptor;
import org.apache.nifi.security.util.crypto.OpenPGPKeyBasedEncryptor;
import org.apache.nifi.security.util.crypto.OpenPGPPasswordBasedEncryptor;
import org.apache.nifi.security.util.crypto.PasswordBasedEncryptor;
import org.apache.nifi.util.StopWatch;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;

@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"encryption", "decryption", "password", "JCE", "KDF", "Argon2", "Bcrypt", "Scrypt", "PBKDF2", "salt", "iv"})
@CapabilityDescription("Encrypts or Decrypts a FlowFile using either symmetric encryption with a raw key or password " +
        "and randomly generated salt, or asymmetric encryption using a public and secret key.")
@SystemResourceConsideration(resource = SystemResource.CPU)
@WritesAttributes({
        @WritesAttribute(attribute = "encryptcontent.action", description = "\"encrypted\" or \"decrypted\" depending on the processor action"),
        @WritesAttribute(attribute = "encryptcontent.algorithm", description = "The algorithm used for the cryptographic operation"),
        @WritesAttribute(attribute = "encryptcontent.cipher_text_length", description = "The cipher text length in bytes (including IV, salt, and delimiters if present). " +
                "Determined from incoming content in decrypt mode; outgoing content in encrypt mode"),
        @WritesAttribute(attribute = "encryptcontent.iv", description = "The Initialization Vector in hex encoding (if present)"),
        @WritesAttribute(attribute = "encryptcontent.iv_length", description = "The IV length in bytes"),
        @WritesAttribute(attribute = "encryptcontent.kdf", description = "The Key Derivation Function used if Password-Based Encryption was enabled. See Admin Guide - Key Derivation Functions"),
        @WritesAttribute(attribute = "encryptcontent.kdf_salt", description = "The KDF-specific salt including algorithm and cost parameters (if present). See Admin Guide - Key Derivation Functions"),
        @WritesAttribute(attribute = "encryptcontent.kdf_salt_length", description = "The KDF salt length in bytes"),
        @WritesAttribute(attribute = "encryptcontent.pbkdf2_iterations", description = "The number of iterations used in " +
                "PBKDF2 KDF (if present). PBKDF2 does not encode the cost parameter in a custom salt"),
        @WritesAttribute(attribute = "encryptcontent.plaintext_length", description = "The plaintext length in bytes. " +
                "Determined from incoming content in encrypt mode; outgoing content in decrypt mode"),
        @WritesAttribute(attribute = "encryptcontent.salt", description = "The raw salt in hex encoding (if present)"),
        @WritesAttribute(attribute = "encryptcontent.salt_length", description = "The raw salt length in bytes"),
        @WritesAttribute(attribute = "encryptcontent.timestamp", description = "The timestamp at which the cryptographic operation occurred in 'yyyy-MM-dd HH:mm:ss.SSS Z' format"),
               })
@DeprecationNotice(
        classNames = {
                "org.apache.nifi.processors.pgp.EncryptContentPGP",
                "org.apache.nifi.processors.pgp.DecryptContentPGP",
                "org.apache.nifi.processors.cipher.DecryptContent",
                "org.apache.nifi.processors.cipher.DecryptContentCompatibility",
                "org.apache.nifi.processors.cipher.EncryptContentAge",
                "org.apache.nifi.processors.cipher.DecryptContentAge",
        },
        reason = "EncryptContentAge or EncryptContentPGP should be used for encrypting new files using standard formatting. DecryptContent supports deciphering historical files."
)
public class EncryptContent extends AbstractProcessor {

    public static final String ENCRYPT_MODE = "Encrypt";
    public static final String DECRYPT_MODE = "Decrypt";

    private static final String WEAK_CRYPTO_ALLOWED_NAME = "allowed";
    private static final String WEAK_CRYPTO_NOT_ALLOWED_NAME = "not-allowed";

    public static final String IV_ATTR = "encryptcontent.iv";
    public static final String IV_LEN_ATTR = "encryptcontent.iv_length";
    public static final String SALT_ATTR = "encryptcontent.salt";
    public static final String SALT_LEN_ATTR = "encryptcontent.salt_length";
    public static final String KDF_SALT_ATTR = "encryptcontent.kdf_salt";
    public static final String KDF_SALT_LEN_ATTR = "encryptcontent.kdf_salt_length";
    public static final String PT_LEN_ATTR = "encryptcontent.plaintext_length";
    public static final String CT_LEN_ATTR = "encryptcontent.cipher_text_length";
    public static final String TS_ATTR = "encryptcontent.timestamp";
    public static final String ACTION_ATTR = "encryptcontent.action";
    public static final String ALGORITHM_ATTR = "encryptcontent.algorithm";
    public static final String KDF_ATTR = "encryptcontent.kdf";

    public static final PropertyDescriptor MODE = new PropertyDescriptor.Builder()
            .name("Mode")
            .displayName("Mode")
            .description("Specifies whether the content should be encrypted or decrypted")
            .required(true)
            .allowableValues(ENCRYPT_MODE, DECRYPT_MODE)
            .defaultValue(ENCRYPT_MODE)
            .build();
    public static final PropertyDescriptor KEY_DERIVATION_FUNCTION = new PropertyDescriptor.Builder()
            .name("key-derivation-function")
            .displayName("Key Derivation Function")
            .description("Specifies the key derivation function to generate the key from the password (and salt)")
            .required(true)
            .allowableValues(buildKeyDerivationFunctionAllowableValues())
            .defaultValue(KeyDerivationFunction.NONE.name())
            .build();
    public static final PropertyDescriptor ENCRYPTION_ALGORITHM = new PropertyDescriptor.Builder()
            .name("Encryption Algorithm")
            .displayName("Encryption Algorithm")
            .description("The Encryption Algorithm to use")
            .required(true)
            .allowableValues(buildEncryptionMethodAllowableValues())
            .defaultValue(EncryptionMethod.AES_GCM.name())
            .build();
    public static final PropertyDescriptor PASSWORD = new PropertyDescriptor.Builder()
            .name("Password")
            .displayName("Password")
            .description("The Password to use for encrypting or decrypting the data")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();
    public static final PropertyDescriptor PUBLIC_KEYRING = new PropertyDescriptor.Builder()
            .name("public-keyring-file")
            .displayName("Public Keyring File")
            .description("In a PGP encrypt mode, this keyring contains the public key of the recipient")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor PUBLIC_KEY_USERID = new PropertyDescriptor.Builder()
            .name("public-key-user-id")
            .displayName("Public Key User Id")
            .description("In a PGP encrypt mode, this user id of the recipient")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor PRIVATE_KEYRING = new PropertyDescriptor.Builder()
            .name("private-keyring-file")
            .displayName("Private Keyring File")
            .description("In a PGP decrypt mode, this keyring contains the private key of the recipient")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor PRIVATE_KEYRING_PASSPHRASE = new PropertyDescriptor.Builder()
            .name("private-keyring-passphrase")
            .displayName("Private Keyring Passphrase")
            .description("In a PGP decrypt mode, this is the private keyring passphrase")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .sensitive(true)
            .build();

    public static final PropertyDescriptor PGP_SYMMETRIC_ENCRYPTION_CIPHER = new PropertyDescriptor.Builder()
            .name("pgp-symmetric-cipher")
            .displayName("PGP Symmetric Cipher")
            .description("When using PGP encryption, this is the symmetric cipher to be used. This property is ignored if "
                    + "Encryption Algorithm is not PGP or PGP-ASCII-ARMOR\nNote that the provided cipher is only used during"
                    + "the encryption phase, while it is inferred from the ciphertext in the decryption phase")
            .required(false)
            .allowableValues(buildPGPSymmetricCipherAllowableValues())
            .defaultValue(String.valueOf(PGPEncryptedData.AES_128))
            .build();

    public static final PropertyDescriptor RAW_KEY_HEX = new PropertyDescriptor.Builder()
            .name("raw-key-hex")
            .displayName("Raw Key (hexadecimal)")
            .description("In keyed encryption, this is the raw key, encoded in hexadecimal")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();
    public static final PropertyDescriptor ALLOW_WEAK_CRYPTO = new PropertyDescriptor.Builder()
            .name("allow-weak-crypto")
            .displayName("Allow insecure cryptographic modes")
            .description("Overrides the default behavior to prevent unsafe combinations of encryption algorithms and short passwords on JVMs with limited strength cryptographic jurisdiction policies")
            .required(true)
            .allowableValues(buildWeakCryptoAllowableValues())
            .defaultValue(buildDefaultWeakCryptoAllowableValue().getValue())
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
            .description("Any FlowFile that is successfully encrypted or decrypted will be routed to success").build();

    public static final Relationship REL_FAILURE = new Relationship.Builder().name("failure")
            .description("Any FlowFile that cannot be encrypted or decrypted will be routed to failure").build();

    private static final DeprecationLogger deprecationLogger = DeprecationLoggerFactory.getLogger(EncryptContent.class);

    private List<PropertyDescriptor> properties;

    private Set<Relationship> relationships;

    static {
        // add BouncyCastle encryption providers
        Security.addProvider(new BouncyCastleProvider());
    }

    private static AllowableValue[] buildKeyDerivationFunctionAllowableValues() {
        final KeyDerivationFunction[] keyDerivationFunctions = KeyDerivationFunction.values();
        List<AllowableValue> allowableValues = new ArrayList<>(keyDerivationFunctions.length);
        for (KeyDerivationFunction kdf : keyDerivationFunctions) {
            allowableValues.add(new AllowableValue(kdf.name(), kdf.getKdfName(), kdf.getDescription()));
        }

        return allowableValues.toArray(new AllowableValue[0]);
    }

    private static AllowableValue[] buildEncryptionMethodAllowableValues() {
        final EncryptionMethod[] encryptionMethods = EncryptionMethod.values();
        List<AllowableValue> allowableValues = new ArrayList<>(encryptionMethods.length);
        for (EncryptionMethod em : encryptionMethods) {
            allowableValues.add(new AllowableValue(em.name(), em.name(), em.toString()));
        }

        return allowableValues.toArray(new AllowableValue[0]);
    }

    private static AllowableValue[] buildWeakCryptoAllowableValues() {
        List<AllowableValue> allowableValues = new ArrayList<>();
        allowableValues.add(new AllowableValue(WEAK_CRYPTO_ALLOWED_NAME, "Allowed", "Operation will not be blocked and no alerts will be presented " +
                "when unsafe combinations of encryption algorithms and passwords are provided"));
        allowableValues.add(buildDefaultWeakCryptoAllowableValue());
        return allowableValues.toArray(new AllowableValue[0]);
    }

    private static AllowableValue buildDefaultWeakCryptoAllowableValue() {
        return new AllowableValue(WEAK_CRYPTO_NOT_ALLOWED_NAME, "Not Allowed", "When set, operation will be blocked and alerts will be presented to the user " +
                "if unsafe combinations of encryption algorithms and passwords are provided on a JVM with limited strength crypto. To fix this, see the Admin Guide.");
    }

    private static AllowableValue[] buildPGPSymmetricCipherAllowableValues() {
        // Allowed values are inferred from SymmetricKeyAlgorithmTags. Note that NULL and SAFER cipher are not supported and therefore not listed
        return new AllowableValue[]{
                new AllowableValue("1", "IDEA"),
                new AllowableValue("2", "TRIPLE_DES"),
                new AllowableValue("3", "CAST5"),
                new AllowableValue("4", "BLOWFISH"),
                new AllowableValue("6", "DES"),
                new AllowableValue("7", "AES_128"),
                new AllowableValue("8", "AES_192"),
                new AllowableValue("9", "AES_256"),
                new AllowableValue("10", "TWOFISH"),
                new AllowableValue("11", "CAMELLIA_128"),
                new AllowableValue("12", "CAMELLIA_192"),
                new AllowableValue("13", "CAMELLIA_256")};
    }

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(MODE);
        properties.add(KEY_DERIVATION_FUNCTION);
        properties.add(ENCRYPTION_ALGORITHM);
        properties.add(ALLOW_WEAK_CRYPTO);
        properties.add(PASSWORD);
        properties.add(RAW_KEY_HEX);
        properties.add(PUBLIC_KEYRING);
        properties.add(PUBLIC_KEY_USERID);
        properties.add(PRIVATE_KEYRING);
        properties.add(PRIVATE_KEYRING_PASSPHRASE);
        properties.add(PGP_SYMMETRIC_ENCRYPTION_CIPHER);
        this.properties = Collections.unmodifiableList(properties);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    public static boolean isPGPAlgorithm(final String algorithm) {
        return algorithm.startsWith("PGP");
    }

    public static boolean isPGPArmoredAlgorithm(final String algorithm) {
        return isPGPAlgorithm(algorithm) && algorithm.endsWith("ASCII-ARMOR");
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext context) {
        final List<ValidationResult> validationResults = new ArrayList<>(super.customValidate(context));
        final String methodValue = context.getProperty(ENCRYPTION_ALGORITHM).getValue();
        final EncryptionMethod encryptionMethod = EncryptionMethod.valueOf(methodValue);
        final String algorithm = encryptionMethod.getAlgorithm();
        final String password = context.getProperty(PASSWORD).getValue();
        final KeyDerivationFunction kdf = KeyDerivationFunction.valueOf(context.getProperty(KEY_DERIVATION_FUNCTION).getValue());
        final String keyHex = context.getProperty(RAW_KEY_HEX).getValue();
        final boolean encrypt = context.getProperty(MODE).getValue().equalsIgnoreCase(ENCRYPT_MODE);
        if (isPGPAlgorithm(algorithm)) {
            deprecationLogger.warn("{}[id={}] OpenPGP support is deprecated: see EncryptContentPGP and DecryptContentPGP",
                    getClass().getSimpleName(),
                    getIdentifier()
            );

            final String publicKeyring = context.getProperty(PUBLIC_KEYRING).getValue();
            final String publicUserId = context.getProperty(PUBLIC_KEY_USERID).getValue();
            final String privateKeyring = context.getProperty(PRIVATE_KEYRING).getValue();
            final String privateKeyringPassphrase = context.getProperty(PRIVATE_KEYRING_PASSPHRASE).evaluateAttributeExpressions().getValue();
            final Integer cipher = context.getProperty(PGP_SYMMETRIC_ENCRYPTION_CIPHER).asInteger();
            validationResults.addAll(validatePGP(encryptionMethod, password, encrypt, publicKeyring, publicUserId,
                    privateKeyring, privateKeyringPassphrase, cipher));
        } else { // Not PGP
            boolean allowWeakCrypto = context.getProperty(ALLOW_WEAK_CRYPTO).getValue().equalsIgnoreCase(WEAK_CRYPTO_ALLOWED_NAME);
            if (encryptionMethod.isKeyedCipher()) { // Raw key or derived key from password
                validationResults.addAll(validateKeyed(encryptionMethod, kdf, keyHex, password, allowWeakCrypto, encrypt));
            } else { // PBE
                validationResults.addAll(validatePBE(encryptionMethod, kdf, password, allowWeakCrypto));
            }
        }
        return validationResults;
    }

    /**
     * Returns true if the integer value provided maps to a valid {@code cipher} as contained in the {@code PGP_SYMMETRIC_ENCRYPTION_CIPHER}.
     *
     * @param cipher an integer indicating a particular cipher
     * @return true if the cipher is supported
     */
    private static boolean isValidCipher(int cipher) {
        return PGP_SYMMETRIC_ENCRYPTION_CIPHER.getAllowableValues().stream().anyMatch(av -> av.getValue().equals(String.valueOf(cipher)));
    }

    private List<ValidationResult> validatePGP(EncryptionMethod encryptionMethod, String password, boolean encrypt,
                                               String publicKeyring, String publicUserId, String privateKeyring,
                                               String privateKeyringPassphrase, int cipher) {
        List<ValidationResult> validationResults = new ArrayList<>();

        if (encrypt && password != null && !isValidCipher(cipher)) {
            validationResults.add(new ValidationResult.Builder().subject(PGP_SYMMETRIC_ENCRYPTION_CIPHER.getDisplayName())
                    .explanation("When performing an encryption with " + encryptionMethod.getAlgorithm() + " and a symmetric " +
                            PASSWORD.getDisplayName() + ", a" + PGP_SYMMETRIC_ENCRYPTION_CIPHER.getDisplayName() + " is required")
                    .build());
        }

        if (password == null) {
            if (encrypt) {
                // If encrypting without a password, require both public-keyring-file and public-key-user-id
                if (publicKeyring == null || publicUserId == null) {
                    validationResults.add(new ValidationResult.Builder().subject(PUBLIC_KEYRING.getDisplayName())
                            .explanation(encryptionMethod.getAlgorithm() + " encryption without a " + PASSWORD.getDisplayName() + " requires both "
                                    + PUBLIC_KEYRING.getDisplayName() + " and " + PUBLIC_KEY_USERID.getDisplayName())
                            .build());
                } else {
                    // Verify the public keyring contains the user id
                    try {
                        if (OpenPGPKeyBasedEncryptor.getPublicKey(publicUserId, publicKeyring) == null) {
                            validationResults.add(new ValidationResult.Builder().subject(PUBLIC_KEYRING.getDisplayName())
                                    .explanation(PUBLIC_KEYRING.getDisplayName() + " " + publicKeyring
                                            + " does not contain user id " + publicUserId)
                                    .build());
                        }
                    } catch (final Exception e) {
                        validationResults.add(new ValidationResult.Builder().subject(PUBLIC_KEYRING.getDisplayName())
                                .explanation("Invalid " + PUBLIC_KEYRING.getDisplayName() + " " + publicKeyring
                                        + " because " + e.toString())
                                .build());
                    }
                }
            } else { // Decrypt
                // Require both private-keyring-file and private-keyring-passphrase
                if (privateKeyring == null || privateKeyringPassphrase == null) {
                    validationResults.add(new ValidationResult.Builder().subject(PRIVATE_KEYRING.getName())
                            .explanation(encryptionMethod.getAlgorithm() + " decryption without a " + PASSWORD.getDisplayName() + " requires both "
                                    + PRIVATE_KEYRING.getDisplayName() + " and " + PRIVATE_KEYRING_PASSPHRASE.getDisplayName())
                            .build());
                } else {
                    final String providerName = encryptionMethod.getProvider();
                    // Verify the passphrase works on the private keyring
                    try {
                        if (!OpenPGPKeyBasedEncryptor.validateKeyring(providerName, privateKeyring, privateKeyringPassphrase.toCharArray())) {
                            validationResults.add(new ValidationResult.Builder().subject(PRIVATE_KEYRING.getDisplayName())
                                    .explanation(PRIVATE_KEYRING.getDisplayName() + " " + privateKeyring
                                            + " could not be opened with the provided " + PRIVATE_KEYRING_PASSPHRASE.getDisplayName())
                                    .build());
                        }
                    } catch (final Exception e) {
                        validationResults.add(new ValidationResult.Builder().subject(PRIVATE_KEYRING.getDisplayName())
                                .explanation("Invalid " + PRIVATE_KEYRING.getDisplayName() + " " + privateKeyring
                                        + " because " + e.toString())
                                .build());
                    }
                }
            }
        }

        return validationResults;
    }

    private List<ValidationResult> validatePBE(EncryptionMethod encryptionMethod, KeyDerivationFunction kdf, String password, boolean allowWeakCrypto) {
        // Start by validating the password specifically
        List<ValidationResult> validationResults = validatePassword(encryptionMethod, kdf, password, allowWeakCrypto);

        // Check the KDF for compatibility with this algorithm
        List<String> kdfsForPBECipher = getKDFsForPBECipher(encryptionMethod);
        if (kdf == null || !kdfsForPBECipher.contains(kdf.name())) {
            final String displayName = KEY_DERIVATION_FUNCTION.getDisplayName();
            validationResults.add(new ValidationResult.Builder().subject(displayName)
                    .explanation(displayName + " is required to be " + StringUtils.join(kdfsForPBECipher,
                            ", ") + " when using algorithm " + encryptionMethod.getAlgorithm() + ". See Admin Guide.").build());
        }

        return validationResults;
    }

    private List<ValidationResult> validatePassword(EncryptionMethod encryptionMethod, KeyDerivationFunction kdf, String password, boolean allowWeakCrypto) {
        List<ValidationResult> validationResults = new ArrayList<>();

        // Password required (short circuits validation because other conditions depend on password presence)
        if (StringUtils.isEmpty(password)) {
            validationResults.add(new ValidationResult.Builder().subject(PASSWORD.getName())
                    .explanation(PASSWORD.getDisplayName() + " is required when using algorithm " + encryptionMethod.getAlgorithm() + " and KDF " + kdf.getKdfName()).build());
            return validationResults;
        }

        // If weak crypto is not explicitly allowed via override, check the password length and algorithm
        final int passwordBytesLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (!allowWeakCrypto) {
            final int minimumSafePasswordLength = PasswordBasedEncryptor.getMinimumSafePasswordLength();
            if (passwordBytesLength < minimumSafePasswordLength) {
                validationResults.add(new ValidationResult.Builder().subject(PASSWORD.getName())
                        .explanation("Password length less than " + minimumSafePasswordLength + " characters is potentially unsafe. See Admin Guide.").build());
            }
        }

        return validationResults;
    }


    private List<ValidationResult> validateKeyed(EncryptionMethod encryptionMethod, KeyDerivationFunction kdf, String keyHex, String password, boolean allowWeakCrypto, boolean encrypt) {
        List<ValidationResult> validationResults = new ArrayList<>();

        int allowedKeyLength = PasswordBasedEncryptor.getMaxAllowedKeyLength(ENCRYPTION_ALGORITHM.getName());

        // Scenario 1: RKH is present & KDF == NONE
        if (kdf == KeyDerivationFunction.NONE) {
            if (StringUtils.isEmpty(keyHex)) {
                validationResults.add(new ValidationResult.Builder().subject(RAW_KEY_HEX.getName())
                        .explanation(RAW_KEY_HEX.getDisplayName() +
                                " is required when using algorithm " +
                                encryptionMethod.getAlgorithm() +
                                " and KDF " +
                                KeyDerivationFunction.NONE +
                                ". See Admin Guide.").build());
            } else {
                validateKeyHex(encryptionMethod, keyHex, validationResults, allowedKeyLength);
            }
        } else if (kdf.isStrongKDF()) {
            // Scenario 2: PW is present & KDF is strong
            if (StringUtils.isEmpty(password)) {
                validationResults.add(new ValidationResult.Builder().subject(PASSWORD.getName())
                        .explanation(PASSWORD.getDisplayName() +
                                " is required when using algorithm " +
                                encryptionMethod.getAlgorithm() +
                                " and KDF " +
                                kdf.getKdfName() +
                                ". See Admin Guide.").build());
            } else {
                // Password must still be validated
                validationResults.addAll(validatePassword(encryptionMethod, kdf, password, allowWeakCrypto));
            }
        } else {
            // KDF is legacy; not eligible for keyed cipher algorithm
            List<String> kdfsForKeyedCipher = getKDFsForKeyedCipher();
            validationResults.add(new ValidationResult.Builder().subject(KEY_DERIVATION_FUNCTION.getName())
                    .explanation(KEY_DERIVATION_FUNCTION.getDisplayName() + " is required to be " + StringUtils.join(kdfsForKeyedCipher, ", ") + " when using algorithm " +
                            encryptionMethod.getAlgorithm()).build());
        }

        if (encrypt && EncryptionMethod.AES_CBC_NO_PADDING == encryptionMethod) {
            validationResults.add(new ValidationResult.Builder()
                    .subject(ENCRYPTION_ALGORITHM.getDisplayName())
                    .input(encryptionMethod.name())
                    .explanation(String.format("Encryption not supported for [%s]", encryptionMethod.getAlgorithm()))
                    .valid(false)
                    .build()
            );
        }

        return validationResults;
    }

    private void validateKeyHex(EncryptionMethod encryptionMethod, String keyHex, List<ValidationResult> validationResults, int allowedKeyLength) {
        byte[] keyBytes = new byte[0];
        try {
            keyBytes = Hex.decodeHex(keyHex.toCharArray());
        } catch (DecoderException e) {
            validationResults.add(new ValidationResult.Builder().subject(RAW_KEY_HEX.getName())
                    .explanation("Key must be valid hexadecimal string. See Admin Guide.").build());
        }
        if (keyBytes.length * 8 > allowedKeyLength) {
            validationResults.add(new ValidationResult.Builder().subject(RAW_KEY_HEX.getName())
                    .explanation("Key length greater than " + allowedKeyLength + " bits is not supported by this JVM" +
                            " due to lacking JCE Unlimited Strength Jurisdiction Policy files. See Admin Guide.").build());
        }
        if (!CipherUtility.isValidKeyLengthForAlgorithm(keyBytes.length * 8, encryptionMethod.getAlgorithm())) {
            List<Integer> validKeyLengths = CipherUtility.getValidKeyLengthsForAlgorithm(encryptionMethod.getAlgorithm());
            validationResults.add(new ValidationResult.Builder().subject(RAW_KEY_HEX.getName())
                    .explanation("Key must be valid length [" + StringUtils.join(validKeyLengths, ", ") + "]. See Admin Guide.").build());
        }
    }

    private List<String> getKDFsForKeyedCipher() {
        List<String> kdfs = Arrays.stream(KeyDerivationFunction.values()).filter(KeyDerivationFunction::isStrongKDF).map(Enum::name).collect(Collectors.toList());
        kdfs.add(KeyDerivationFunction.NONE.name());
        return kdfs;
    }

    private List<String> getKDFsForPBECipher(EncryptionMethod encryptionMethod) {
        List<String> kdfsForPBECipher;
        if (encryptionMethod.isCompatibleWithStrongKDFs()) {
            // Collect all
            kdfsForPBECipher = Arrays.stream(KeyDerivationFunction.values()).map(Enum::name).collect(Collectors.toList());
        } else {
            // Collect all weak KDFS
            kdfsForPBECipher = Arrays.stream(KeyDerivationFunction.values()).filter(kdf -> !kdf.isStrongKDF()).map(Enum::name).collect(Collectors.toList());
        }

        kdfsForPBECipher.remove(KeyDerivationFunction.NONE.name());
        return kdfsForPBECipher;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final ComponentLog logger = getLogger();
        final String method = context.getProperty(ENCRYPTION_ALGORITHM).getValue();
        final EncryptionMethod encryptionMethod = EncryptionMethod.valueOf(method);
        final String providerName = encryptionMethod.getProvider();
        final String algorithm = encryptionMethod.getAlgorithm();
        final Integer pgpCipher = context.getProperty(PGP_SYMMETRIC_ENCRYPTION_CIPHER).asInteger();
        final String password = context.getProperty(PASSWORD).getValue();
        final KeyDerivationFunction kdf = KeyDerivationFunction.valueOf(context.getProperty(KEY_DERIVATION_FUNCTION).getValue());
        final boolean encrypt = context.getProperty(MODE).getValue().equalsIgnoreCase(ENCRYPT_MODE);

        Encryptor encryptor;
        StreamCallback callback;
        try {
            if (isPGPAlgorithm(algorithm)) {
                encryptor = createPGPEncryptor(context, flowFile, providerName, algorithm, pgpCipher, password, encrypt);
            } else if (kdf.equals(KeyDerivationFunction.NONE)) { // Raw key
                encryptor = createKeyedEncryptor(context, encryptionMethod);
            } else { // PBE
                encryptor = createPBEEncryptor(encryptionMethod, password, kdf);
            }

            if (encrypt) {
                callback = encryptor.getEncryptionCallback();
            } else {
                callback = encryptor.getDecryptionCallback();
            }

        } catch (final Exception e) {
            logger.error("Failed to initialize {}cryption algorithm", encrypt ? "en" : "de", e);
            session.rollback();
            context.yield();
            return;
        }

        try {
            final StopWatch stopWatch = new StopWatch(true);
            flowFile = session.write(flowFile, callback);

            // Update the flowfile attributes
            Map<String, String> clonedAttributes = new HashMap<>(flowFile.getAttributes());
            encryptor.updateAttributes(clonedAttributes);
            flowFile = session.putAllAttributes(flowFile, clonedAttributes);

            logger.info("successfully {}crypted {}", encrypt ? "en" : "de", flowFile);
            session.getProvenanceReporter().modifyContent(flowFile, stopWatch.getElapsed(TimeUnit.MILLISECONDS));
            session.transfer(flowFile, REL_SUCCESS);
        } catch (final ProcessException e) {
            logger.error("Cannot {}crypt {}", encrypt ? "en" : "de", flowFile, e);
            session.transfer(flowFile, REL_FAILURE);
        }
    }

    private Encryptor createPGPEncryptor(ProcessContext context, FlowFile flowFile, String providerName, String algorithm, Integer pgpCipher, String password, boolean encrypt) {
        Encryptor encryptor;
        final String filename = flowFile.getAttribute(CoreAttributes.FILENAME.key());
        final String publicKeyring = context.getProperty(PUBLIC_KEYRING).getValue();
        final String privateKeyring = context.getProperty(PRIVATE_KEYRING).getValue();
        if (encrypt && publicKeyring != null) {
            final String publicUserId = context.getProperty(PUBLIC_KEY_USERID).getValue();
            encryptor = new OpenPGPKeyBasedEncryptor(algorithm, pgpCipher, providerName, publicKeyring, publicUserId, null, filename);
        } else if (!encrypt && privateKeyring != null) {
            final char[] keyringPassphrase = context.getProperty(PRIVATE_KEYRING_PASSPHRASE).evaluateAttributeExpressions().getValue().toCharArray();
            encryptor = new OpenPGPKeyBasedEncryptor(algorithm, pgpCipher, providerName, privateKeyring, null, keyringPassphrase, filename);
        } else {
            final char[] passphrase = Normalizer.normalize(password, Normalizer.Form.NFC).toCharArray();
            encryptor = new OpenPGPPasswordBasedEncryptor(algorithm, pgpCipher, providerName, passphrase, filename);
        }
        return encryptor;
    }

    private Encryptor createKeyedEncryptor(ProcessContext context, EncryptionMethod encryptionMethod) throws DecoderException {
        Encryptor encryptor;
        final String keyHex = context.getProperty(RAW_KEY_HEX).getValue();
        encryptor = new KeyedEncryptor(encryptionMethod, Hex.decodeHex(keyHex.toCharArray()));
        return encryptor;
    }

    private Encryptor createPBEEncryptor(EncryptionMethod encryptionMethod, String password, KeyDerivationFunction kdf) {
        Encryptor encryptor;
        final char[] passphrase = Normalizer.normalize(password, Normalizer.Form.NFC).toCharArray();
        encryptor = new PasswordBasedEncryptor(encryptionMethod, passphrase, kdf);
        return encryptor;
    }

    public interface Encryptor {
        StreamCallback getEncryptionCallback() throws Exception;

        StreamCallback getDecryptionCallback() throws Exception;

        void updateAttributes(Map<String, String> attributes);
    }

}
