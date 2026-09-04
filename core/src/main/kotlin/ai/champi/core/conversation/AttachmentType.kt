package ai.champi.core.conversation

/** Discriminates what kind of content is attached to a message from the share-sheet receiver. */
enum class AttachmentType {
    /** A raster image (MIME type image) copied to the app's cache directory. */
    IMAGE,

    /** Any non-image file (e.g. application/pdf), referenced by a cache-local path. */
    FILE,
}
