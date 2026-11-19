/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.attachment;

import com.mojang.logging.LogUtils;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Implementation class for objects that can hold data attachments.
 * For the user-facing methods, see {@link IAttachmentHolder}.
 */
public abstract class AttachmentHolder implements IAttachmentHolder {
    public static final String ATTACHMENTS_NBT_KEY = "neoforge:attachments";
    private static final boolean IN_DEV = !FMLLoader.isProduction();
    private static final Logger LOGGER = LogUtils.getLogger();

    private void validateAttachmentType(AttachmentType<?> type) {
        Objects.requireNonNull(type);
        if (!IN_DEV) return;

        if (!NeoForgeRegistries.ATTACHMENT_TYPES.containsValue(type)) {
            throw new IllegalArgumentException("Data attachment type with default value " + type.defaultValueSupplier.apply(getExposedHolder()) + " must be registered!");
        }
    }

    @Nullable
    volatile Map<AttachmentType<?>, Object> attachments = null;

    /**
     * Create the attachment map if it does not yet exist, or return the current map.
     */
    synchronized final Map<AttachmentType<?>, Object> getAttachmentMap() {
        Map<AttachmentType<?>, Object> map = attachments;
        if (map == null) {
            map = new IdentityHashMap<>(4);
            attachments = map;
        }
        return map;
    }

    /**
     * Returns the attachment holder that is exposed to the user.
     * This is the same as {@code this} for most cases,
     * but when using {@link AsField} it is the field holder.
     */
    IAttachmentHolder getExposedHolder() {
        return this;
    }

    @Override
    public final boolean hasAttachments() {
        net.neoforged.neoforge.server.threading.RegionThreadingHooks.assertAttachmentHolderAccess(getExposedHolder(), "AttachmentHolder.hasAttachments");
        final Map<AttachmentType<?>, Object> map = attachments;
        if (map == null) {
            return false;
        }
        synchronized (this) {
            return !map.isEmpty();
        }
    }

    @Override
    public final boolean hasData(AttachmentType<?> type) {
        validateAttachmentType(type);
        net.neoforged.neoforge.server.threading.RegionThreadingHooks.assertAttachmentHolderAccess(getExposedHolder(), "AttachmentHolder.hasData");
        final Map<AttachmentType<?>, Object> map = attachments;
        if (map == null) {
            return false;
        }
        synchronized (this) {
            return map.containsKey(type);
        }
    }

    @Override
    public final <T> T getData(AttachmentType<T> type) {
        validateAttachmentType(type);
        net.neoforged.neoforge.server.threading.RegionThreadingHooks.assertAttachmentHolderAccess(getExposedHolder(), "AttachmentHolder.getData");
        T ret;
        boolean created = false;
        synchronized (this) {
            final Map<AttachmentType<?>, Object> map = getAttachmentMap();
            ret = (T) map.get(type);
            if (ret == null) {
                ret = type.defaultValueSupplier.apply(getExposedHolder());
                map.put(type, ret);
                created = true;
            }
        }
        if (created) {
            syncData(type);
        }
        return ret;
    }

    @Override
    @Nullable
    public <T> T getExistingDataOrNull(AttachmentType<T> type) {
        validateAttachmentType(type);
        net.neoforged.neoforge.server.threading.RegionThreadingHooks.assertAttachmentHolderAccess(getExposedHolder(), "AttachmentHolder.getExistingDataOrNull");
        final Map<AttachmentType<?>, Object> map = this.attachments;
        if (map == null) {
            return null;
        }
        synchronized (this) {
            return (T) map.get(type);
        }
    }

    @Override
    @MustBeInvokedByOverriders
    public <T> @Nullable T setData(AttachmentType<T> type, T data) {
        validateAttachmentType(type);
        Objects.requireNonNull(data);
        net.neoforged.neoforge.server.threading.RegionThreadingHooks.assertAttachmentHolderAccess(getExposedHolder(), "AttachmentHolder.setData");
        final T previousData;
        synchronized (this) {
            previousData = (T) getAttachmentMap().put(type, data);
        }
        syncData(type);
        return previousData;
    }

    @Override
    @MustBeInvokedByOverriders
    public <T> @Nullable T removeData(AttachmentType<T> type) {
        validateAttachmentType(type);
        net.neoforged.neoforge.server.threading.RegionThreadingHooks.assertAttachmentHolderAccess(getExposedHolder(), "AttachmentHolder.removeData");
        final Map<AttachmentType<?>, Object> map = this.attachments;
        if (map == null) {
            return null;
        }
        final T previousData;
        synchronized (this) {
            previousData = (T) map.remove(type);
        }
        syncData(type);
        return previousData;
    }

    /**
     * Writes the serializable attachments to a tag.
     * Returns {@code null} if there are no serializable attachments.
     */
    @Nullable
    public final CompoundTag serializeAttachments(HolderLookup.Provider provider) {
        if (attachments == null) {
            return null;
        }
        CompoundTag tag = null;
        for (var entry : attachments.entrySet()) {
            var type = entry.getKey();
            var key = NeoForgeRegistries.ATTACHMENT_TYPES.getKey(type);
            if (type.serializer != null) {
                try {
                    Tag serialized = ((IAttachmentSerializer<?, Object>) type.serializer).write(entry.getValue(), provider);
                    if (serialized != null) {
                        if (tag == null)
                            tag = new CompoundTag();
                        tag.put(key.toString(), serialized);
                    }
                } catch (Exception exception) {
                    LOGGER.error("Failed to serialize data attachment {}. Skipping.", key, exception);
                }
            }
        }
        return tag;
    }

    /**
     * Reads serializable attachments from a tag previously created via {@link #serializeAttachments(HolderLookup.Provider)}.
     *
     * <p>This does not trigger {@link IAttachmentHolder#syncData syncing} of the deserialized attachments.
     */
    protected final void deserializeAttachments(HolderLookup.Provider provider, CompoundTag tag) {
        for (var key : tag.getAllKeys()) {
            // Use tryParse to not discard valid attachment type keys, even if there is a malformed key.
            ResourceLocation keyLocation = ResourceLocation.tryParse(key);
            if (keyLocation == null) {
                LOGGER.error("Encountered invalid data attachment key {}. Skipping.", key);
                continue;
            }

            var type = NeoForgeRegistries.ATTACHMENT_TYPES.get(keyLocation);
            if (type == null || type.serializer == null) {
                LOGGER.error("Encountered unknown or non-serializable data attachment {}. Skipping.", key);
                continue;
            }

            try {
                getAttachmentMap().put(type, ((IAttachmentSerializer<Tag, ?>) type.serializer).read(getExposedHolder(), tag.get(key), provider));
            } catch (Exception exception) {
                LOGGER.error("Failed to deserialize data attachment {}. Skipping.", key, exception);
            }
        }
    }

    /**
     * Version of the {@link AttachmentHolder} that is suitable for storing in a field.
     * To be used when extending {@link AttachmentHolder} is not possible,
     * for example because the class already has a supertype.
     */
    public static class AsField extends AttachmentHolder {
        private final IAttachmentHolder exposedHolder;

        public AsField(IAttachmentHolder exposedHolder) {
            this.exposedHolder = exposedHolder;
        }

        @Override
        IAttachmentHolder getExposedHolder() {
            return exposedHolder;
        }

        public void deserializeInternal(HolderLookup.Provider provider, CompoundTag tag) {
            deserializeAttachments(provider, tag);
        }

        @Override
        public void syncData(AttachmentType<?> type) {
            exposedHolder.syncData(type);
        }
    }
}
