package com.plankton.liveness.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoUtilsTest {

    private CryptoUtils cryptoUtils;

    @BeforeEach
    void setUp() {
        cryptoUtils = new CryptoUtils();
        ReflectionTestUtils.setField(cryptoUtils, "secret", "test-secret-123");
    }

    @Test
    void hash_mesmoCpfMesmoSecret_retornaMesmoValor() {
        String resultado1 = cryptoUtils.hash("12345678901");
        String resultado2 = cryptoUtils.hash("12345678901");

        assertThat(resultado1).isEqualTo(resultado2);
    }

    @Test
    void hash_cpfsDiferentes_retornamValoresDiferentes() {
        String hash1 = cryptoUtils.hash("12345678901");
        String hash2 = cryptoUtils.hash("99999999999");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void hash_secretsDiferentes_retornamValoresDiferentes() {
        CryptoUtils outroUtils = new CryptoUtils();
        ReflectionTestUtils.setField(outroUtils, "secret", "outro-secret-456");

        String hash1 = cryptoUtils.hash("12345678901");
        String hash2 = outroUtils.hash("12345678901");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void hash_retornaHexadecimalLowercase() {
        String resultado = cryptoUtils.hash("12345678901");

        assertThat(resultado).matches("[0-9a-f]+");
    }

    @Test
    void hash_retorna64Caracteres() {
        String resultado = cryptoUtils.hash("12345678901");

        assertThat(resultado).hasSize(64);
    }

    @Test
    void hash_retornaValorHmacCorreto() {
        // Vetor de referência gerado via: echo -n "12345678901" | openssl dgst -sha256 -hmac "test-secret-123"
        String esperado = "55807e4b789426e23b270dbad2bb2b27da5bdb9cb8f62476f295e117f1ad6936";

        assertThat(cryptoUtils.hash("12345678901")).isEqualTo(esperado);
    }
}
