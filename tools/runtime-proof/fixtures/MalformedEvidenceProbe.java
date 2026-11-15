/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

/** Service fault injection exercises the real decoder; no Core authority is issued. */
public final class MalformedEvidenceProbe {
    private static final class Victim {
        int value;
        int read() { return value; }
    }

    private static native void configureSnapshot(byte[] snapshot, boolean enabled);
    private static int rejected;

    private static byte[] minimal() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var data = new DataOutputStream(bytes);
        data.writeInt(0x46524731);
        data.writeLong(RuntimeProof.epoch());
        data.writeInt(4);
        data.writeInt(1);
        data.writeInt(0);
        data.writeInt(0);
        data.writeInt(0);
        return bytes.toByteArray();
    }

    private static byte[] withMethod(int codeLength, int handlers) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var data = new DataOutputStream(bytes);
        data.write(minimal(), 0, 24);
        data.writeInt(1);
        data.writeInt(1);
        data.writeUTF("read");
        data.writeUTF("()I");
        data.writeInt(codeLength);
        data.write(new byte[] {3, (byte) 172});
        data.writeInt(handlers);
        data.writeInt(1);
        data.writeInt(0);
        data.writeUTF("value");
        data.writeUTF("I");
        return bytes.toByteArray();
    }

    private static void refuse(String name, byte[] snapshot) {
        configureSnapshot(snapshot, true);
        if (RuntimeProof.definition(Victim.class) != null)
            throw new AssertionError("Malformed VM evidence accepted: " + name);
        rejected++;
    }

    private static byte[] intAt(byte[] original, int offset, int value) {
        var bytes = original.clone();
        ByteBuffer.wrap(bytes).putInt(offset, value);
        return bytes;
    }

    public static void main(String[] args) throws Exception {
        new Victim().read();
        if (!RuntimeProof.enforcementAvailable() || RuntimeProof.definition(Victim.class) == null)
            throw new AssertionError("Positive installed-evidence control missing");
        byte[] valid = minimal();
        configureSnapshot(valid, true);
        if (RuntimeProof.definition(Victim.class) == null)
            throw new AssertionError("Fault injector failed its structurally valid control");
        refuse("missing", null);
        for (int size = 0; size < valid.length; size++) refuse("truncated-" + size, Arrays.copyOf(valid, size));
        refuse("oversized", new byte[4 * 1024 * 1024 + 1]);
        refuse("magic", intAt(valid, 0, 0));
        refuse("stale-epoch", intAt(valid, 4, Integer.MAX_VALUE));
        refuse("pool-zero", intAt(valid, 16, 0));
        refuse("pool-overflow", intAt(valid, 16, Integer.MAX_VALUE));
        refuse("pool-length-negative", intAt(valid, 20, -1));
        refuse("pool-length-overflow", intAt(valid, 20, Integer.MAX_VALUE));
        refuse("method-count-negative", intAt(valid, 24, -1));
        refuse("method-count-overflow", intAt(valid, 24, Integer.MAX_VALUE));
        refuse("field-count-negative", intAt(valid, 28, -1));
        refuse("field-count-overflow", intAt(valid, 28, Integer.MAX_VALUE));
        refuse("trailing", Arrays.copyOf(valid, valid.length + 1));
        byte[] withMembers = withMethod(2, 0);
        configureSnapshot(withMembers, true);
        if (RuntimeProof.definition(Victim.class) == null)
            throw new AssertionError("Member decoder positive control missing");
        for (int size = 24; size < withMembers.length; size++)
            refuse("truncated-members-" + size, Arrays.copyOf(withMembers, size));
        refuse("code-length-negative", withMethod(-1, 0));
        refuse("code-length-overflow", withMethod(Integer.MAX_VALUE, 0));
        refuse("handlers-negative", withMethod(2, -1));
        refuse("handlers-overflow", withMethod(2, 65536));
        configureSnapshot(null, false);
        if (RuntimeProof.definition(Victim.class) == null)
            throw new AssertionError("Actual VM evidence not restored");
        System.out.println("MALFORMED_VM_EVIDENCE_REFUSED cases=" + rejected
                + " actualDefinitionRestored=true noCoreAuthorityIssued=true productionHasNoInjectionApi=true");
    }
}
