package com.ddd.refactor.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * A placeholder "embedder" that in production would call the real
 * OpenAI embedding API or a local model to produce a float[] vector.
 * TODO - have to make it pord grade
 * @author kiransahoo
 */
public class OpenAiEmbedder {

    private static final int EMBED_DIM = 64;
    private final Random rand = new Random();

    public float[] embed(String text) {
       //for now pesudo random hashing
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            rand.setSeed(byteArrayToLong(hash));
        } catch (NoSuchAlgorithmException e) {
            rand.setSeed(text.hashCode());
        }

        float[] vec = new float[EMBED_DIM];
        for (int i = 0; i < EMBED_DIM; i++) {
            vec[i] = (rand.nextFloat() * 2 - 1);
        }
        return vec;
    }

    private long byteArrayToLong(byte[] bytes) {
        long value = 0;
        for (int i = 0; i < Math.min(bytes.length, 8); i++) {
            value = (value << 8) + (bytes[i] & 0xff);
        }
        return value;
    }
}
