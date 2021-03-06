package edu.wpi.first.shuffleboard.api.sources.recording;

import edu.wpi.first.shuffleboard.api.data.DataType;
import edu.wpi.first.shuffleboard.api.data.DataTypes;
import edu.wpi.first.shuffleboard.api.data.types.StringArrayType;
import edu.wpi.first.shuffleboard.api.data.types.StringType;
import edu.wpi.first.shuffleboard.api.sources.recording.serialization.Serializers;
import edu.wpi.first.shuffleboard.api.sources.recording.serialization.TypeAdapter;

import com.google.common.primitives.Bytes;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@SuppressWarnings("PMD.GodClass")
public final class Serialization {

  private static final Logger log = Logger.getLogger(Serialization.class.getName());

  /**
   * A magic number that is always the first entry in a recording file. This helps check (but does not guarantee) that
   * a loaded file is a valid recording file.
   */
  public static final int MAGIC_NUMBER = 0xFEEDBAC4;

  /**
   * The size of a serialized {@code byte}, in bytes.
   */
  public static final int SIZE_OF_BYTE = 1;
  /**
   * The size of a serialized {@code boolean}, in bytes.
   */
  public static final int SIZE_OF_BOOL = 1;
  /**
   * The size of a serialized {@code short}, in bytes.
   */
  public static final int SIZE_OF_SHORT = 2;
  /**
   * The size of a serialized {@code int}, in bytes.
   */
  public static final int SIZE_OF_INT = 4;
  /**
   * The size of a serialized {@code short}, in bytes.
   */
  public static final int SIZE_OF_LONG = 8;
  /**
   * The size of a serialized {@code double}, in bytes.
   */
  public static final int SIZE_OF_DOUBLE = 8;

  /**
   * Constant offsets for the binary save files.
   */
  private static final class Offsets {
    /**
     * The offset to the magic header number.
     */
    public static final int MAGIC_NUMBER_OFFSET = 0;
    /**
     * The offset to the <tt>int</tt> value of the number of data points.
     */
    public static final int NUMBER_DATA_POINTS_OFFSET = MAGIC_NUMBER_OFFSET + SIZE_OF_INT;
    /**
     * The offset to the <tt>int</tt> value of the number of entries in the constant pool.
     */
    public static final int CONSTANT_POOL_HEADER_OFFSET = NUMBER_DATA_POINTS_OFFSET + SIZE_OF_INT;
  }

  private Serialization() {
  }

  /**
   * Saves a recording to the given file.
   *
   * @param recording the recording to save
   * @param file      the file to save to
   *
   * @throws IOException if the recording could not be saved to the given file
   */
  public static void saveRecording(Recording recording, Path file) throws IOException {
    // work on a copy of the data so changes to the recording don't mess this up
    final List<TimestampedData> dataCopy = new ArrayList<>(recording.getData());
    dataCopy.sort(TimestampedData::compareTo); // make sure the data is sorted properly
    final byte[] header = header(dataCopy); // NOPMD
    final List<String> sourceNames = getAllSourceNames(dataCopy);
    if (sourceNames.size() > Short.MAX_VALUE) {
      throw new IOException("Too many sources (" + sourceNames.size() + "), should be at most " + Short.MAX_VALUE);
    }
    List<byte[]> segments = new ArrayList<>();
    segments.add(header);
    for (TimestampedData data : dataCopy) {
      final DataType type = data.getDataType();
      final Object value = data.getData();

      final byte[] timestamp = toByteArray(data.getTimestamp());
      // use int16 instead of int32 -- 32,767 sources should be enough
      final byte[] sourceIdIndex = toByteArray((short) sourceNames.indexOf(data.getSourceId()));
      final byte[] dataType = toByteArray(type.getName());
      final byte[] dataBytes = encode(value, type);

      segments.add(timestamp);
      segments.add(sourceIdIndex);
      segments.add(dataType);
      segments.add(dataBytes);
    }
    byte[] all = segments
        .stream()
        .reduce(new byte[0], Bytes::concat);
    Path saveDir = file.getParent();
    if (saveDir != null) {
      Files.createDirectories(saveDir);
    }
    Files.write(file, all);
  }

  /**
   * Updates a saved recording file with the contents of the given recording. Note: the recording should <i>not</i>
   * contain any data that has already been saved to disk, or it will be saved again.
   *
   * @param recording the recording to update the save file with
   * @param file      the path to the save file to update
   *
   * @throws IOException if the save file could not be updated
   */
  public static void updateRecordingSave(Recording recording, Path file) throws IOException {
    if (Files.notExists(file) || Files.size(file) == 0) {
      saveRecording(recording, file);
      return;
    }
    if (recording.getData().isEmpty()) {
      // No new data
      return;
    }
    // Use a RandomAccessFile to avoid having to read its entire contents into memory
    RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
    int magic = raf.readInt();
    if (magic != MAGIC_NUMBER) {
      throw new IOException("Wrong magic number in the header. Expected " + MAGIC_NUMBER + ", but was " + magic);
    }

    // Update the number of data points
    raf.seek(Offsets.NUMBER_DATA_POINTS_OFFSET);
    int numExistingDataPoints = raf.readInt();
    int totalDataPoints = numExistingDataPoints + recording.getData().size();
    raf.seek(Offsets.NUMBER_DATA_POINTS_OFFSET);
    raf.writeInt(totalDataPoints); // Update the number of data points

    // Update the constant pool
    raf.seek(Offsets.CONSTANT_POOL_HEADER_OFFSET);
    int numExistingSourceIds = raf.readInt();
    byte[] sourceIdBuffer = new byte[256];
    final List<String> sourceIds = new ArrayList<>();
    int constantPoolSize = 0;
    int pos = Offsets.CONSTANT_POOL_HEADER_OFFSET + SIZE_OF_INT;
    for (int i = 0; i < numExistingSourceIds; i++) {
      raf.seek(pos);
      final int sourceIdLength = raf.readInt();
      pos += SIZE_OF_INT;
      constantPoolSize += SIZE_OF_INT;
      raf.seek(pos);
      final int len = raf.read(sourceIdBuffer, 0, sourceIdLength);
      String sourceId = new String(Arrays.copyOfRange(sourceIdBuffer, 0, len), StandardCharsets.UTF_8);
      pos += len;
      constantPoolSize += len;
      sourceIds.add(sourceId);
    }
    byte[] newConstantPoolEntries = recording.getData().stream()
        .map(TimestampedData::getSourceId)
        .distinct()
        .filter(id -> !sourceIds.contains(id))
        .map(Serialization::toByteArray)
        .reduce(new byte[0], Bytes::concat);
    recording.getData().stream()
        .map(TimestampedData::getSourceId)
        .distinct()
        .filter(s -> !sourceIds.contains(s))
        .forEachOrdered(sourceIds::add);
    byte[] newBodyEntries = recording.getData().stream()
        .map(data -> {
          final DataType type = data.getDataType();
          final Object value = data.getData();

          final byte[] timestamp = toByteArray(data.getTimestamp());
          // use int16 instead of int32 -- 32,767 sources should be enough
          final byte[] sourceIdIndex = toByteArray((short) sourceIds.indexOf(data.getSourceId()));
          final byte[] dataType = toByteArray(type.getName());
          final byte[] dataBytes = encode(value, type);

          return Bytes.concat(timestamp, sourceIdIndex, dataType, dataBytes);
        })
        .reduce(new byte[0], Bytes::concat);

    if (newConstantPoolEntries.length > 0) {
      // Update the number of source IDs
      int totalNumSourceIds = sourceIds.size();
      raf.seek(Offsets.CONSTANT_POOL_HEADER_OFFSET);
      raf.writeInt(totalNumSourceIds);

      // Update the constant pool
      int constantPoolEnd = Offsets.CONSTANT_POOL_HEADER_OFFSET + SIZE_OF_INT + constantPoolSize;
      insertDataIntoFile(file, constantPoolEnd, newConstantPoolEntries);
    }
    Files.write(file, newBodyEntries, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
  }

  /**
   * Inserts binary data into a file at a specific position.
   *
   * @param path the path to the file to insert into
   * @param pos  the position in the file to insert the data at
   * @param data the data to insert
   *
   * @throws IOException if the file could not be modified
   */
  static void insertDataIntoFile(Path path, long pos, byte[] data) throws IOException {
    if (Files.notExists(path)) {
      throw new NoSuchFileException("The file specified does not exist: " + path);
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      // Make sure it's a normal file (not a directory and not a symlink/shortcut to another file)
      throw new IllegalArgumentException("Cannot insert into " + path);
    }
    Objects.requireNonNull(data, "Data cannot be null");
    if (data.length == 0) {
      // Nothing to insert, do nothing
      return;
    }
    Path fileName = path.getFileName();
    if (fileName == null) {
      // Shouldn't be possible
      throw new AssertionError("File name is null - this should have been checked earlier");
    }
    File tmpFile = new File(path.toString().replace(fileName.toString(), "." + fileName));
    RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
    RandomAccessFile tmp = new RandomAccessFile(tmpFile, "rw");
    try (FileChannel src = file.getChannel(); FileChannel dst = tmp.getChannel()) {
      final long fileSize = file.length();
      final long count = fileSize - pos;
      src.transferTo(pos, count, dst);
      src.truncate(pos);
      file.seek(pos);
      file.write(data);
      long newOffset = file.getFilePointer();
      dst.position(0);
      src.transferFrom(dst, newOffset, count);
    } finally {
      boolean deleted = tmpFile.delete();
      if (!deleted) {
        log.fine(() -> "Could not delete temp file");
      }
    }
  }

  public static <T> byte[] encode(T value) {
    return encode(value, (DataType<T>) DataTypes.getDefault().forJavaType(value.getClass()).get());
  }

  /**
   * Encodes a value as a byte array.
   */
  public static <T> byte[] encode(T value, DataType<T> type) {
    return Serializers.getOptional(type)
        .map(s -> s.serialize(value))
        .orElseThrow(() -> new NoSuchElementException("No serializer for " + type));
  }

  /**
   * Decodes a byte buffer as a value of the given data type.
   *
   * @param buffer         the buffer to read from
   * @param bufferPosition the position in the buffer to start reading from
   * @param type           the type of the data to decode
   */
  public static <T> T decode(byte[] buffer, int bufferPosition, DataType<T> type) {
    return Serializers.get(type).deserialize(buffer, bufferPosition);
  }

  /**
   * Loads the recording stored in the given file.
   *
   * @param file the recording file to load
   *
   * @throws IOException if the file could not be read, or if it is in an unexpected binary format
   */
  public static Recording loadRecording(Path file) throws IOException {
    final byte[] bytes = Files.readAllBytes(file);
    if (bytes.length < 8) {
      throw new IOException("Recording file too small");
    }
    final int magic = readInt(bytes, Offsets.MAGIC_NUMBER_OFFSET);
    if (magic != MAGIC_NUMBER) {
      throw new IOException("Wrong magic number in the header. Expected " + MAGIC_NUMBER + ", but was " + magic);
    }
    Serializers.getAdapters().forEach(a -> a.setCurrentFile(file.toFile()));
    //final int numDataPoints = readInt(bytes, Offsets.NUMBER_DATA_POINTS_OFFSET);
    final String[] sourceNames = readStringArray(bytes, Offsets.CONSTANT_POOL_HEADER_OFFSET);

    Recording recording = new Recording();
    int cursor = Offsets.CONSTANT_POOL_HEADER_OFFSET + sizeOfStringArray(sourceNames);
    while (cursor < bytes.length) {
      final long timeStamp = readLong(bytes, cursor); // NOPMD
      cursor += SIZE_OF_LONG;
      final short sourceIdIndex = readShort(bytes, cursor);
      final String sourceId = sourceNames[sourceIdIndex]; //NOPMD
      cursor += SIZE_OF_SHORT;
      final String dataType = readString(bytes, cursor);
      cursor += SIZE_OF_INT + dataType.length();

      final Object value;
      final Optional<DataType> type = DataTypes.getDefault().forName(dataType);
      if (type.isPresent() && Serializers.hasSerializer(type.get())) {
        TypeAdapter adapter = Serializers.get(type.get());
        value = adapter.deserialize(bytes, cursor);
        cursor += adapter.getSerializedSize(value);
      } else {
        throw new IOException("No serializer for data type '" + dataType + "'");
      }

      // Since the data is guaranteed to be ordered in the file, we call recording.append()
      // instead of recording.add() because the latter sorts the data
      TimestampedData data = new TimestampedData(
          sourceId, DataTypes.getDefault().forName(dataType).get(), value, timeStamp);
      recording.append(data);
    }
    return recording;
  }

  /**
   * Gets the size of a string array if it were encoded as a byte array with {@link #toByteArray(String[])}.
   */
  public static int sizeOfStringArray(String[] array) { // NOPMD varargs
    int size = SIZE_OF_INT;
    for (String s : array) {
      size += s.length() + SIZE_OF_INT;
    }
    return size;
  }

  /**
   * Generates a header for a serialized recording. The header contains:
   * <ul>
   * <li>The {@link #MAGIC_NUMBER} magic number, to help confirm data integrity</li>
   * <li>The number of data points (signed 32-bit int)</li>
   * <li>The names of all the recorded sources, used for caching</li>
   * </ul>
   */
  public static byte[] header(List<TimestampedData> data) {
    List<String> sourceNames = getAllSourceNames(data);
    byte[] nameBytes = toByteArray(getAllSourceNames(data).toArray(new String[sourceNames.size()]));
    byte[] header = new byte[(SIZE_OF_INT * 2) + nameBytes.length];
    put(header, toByteArray(MAGIC_NUMBER), 0);
    put(header, toByteArray(data.size()), SIZE_OF_INT);
    put(header, nameBytes, SIZE_OF_INT * 2);
    return header;
  }

  /**
   * Gets the names of all the sources represented in a data set. This is sorted alphabetically.
   */
  public static List<String> getAllSourceNames(List<TimestampedData> data) {
    return data.stream()
        .map(TimestampedData::getSourceId)
        .distinct()
        .sorted()
        .collect(Collectors.toList());
  }

  /**
   * Encodes a boolean as a 1-byte array.
   */
  public static byte[] toByteArray(boolean val) {
    return new byte[]{(byte) (val ? 1 : 0)};
  }

  /**
   * Encodes a 16-bit int as a 2-byte big-endian byte array.
   */
  public static byte[] toByteArray(short val) {
    return new byte[]{
        (byte) ((val >> 8) & 0xFF),
        (byte) (val & 0xFF)
    };
  }

  /**
   * Encodes a 32-bit int as a 4-byte big-endian byte array.
   *
   * @param val the int to encode
   */
  public static byte[] toByteArray(int val) {
    return new byte[]{
        (byte) ((val >> 24) & 0xFF),
        (byte) ((val >> 16) & 0xFF),
        (byte) ((val >> 8) & 0xFF),
        (byte) (val & 0xFF)
    };
  }

  /**
   * Encodes a 64-bit int as an 8-byte big-endian byte array.
   *
   * @param val the int to encode
   */
  public static byte[] toByteArray(long val) {
    return new byte[]{
        (byte) ((val >> 56) & 0xFF),
        (byte) ((val >> 48) & 0xFF),
        (byte) ((val >> 40) & 0xFF),
        (byte) ((val >> 32) & 0xFF),
        (byte) ((val >> 24) & 0xFF),
        (byte) ((val >> 16) & 0xFF),
        (byte) ((val >> 8) & 0xFF),
        (byte) (val & 0xFF)
    };
  }

  /**
   * Encodes a double-precision number as an 8-byte big-endian byte array.
   *
   * @param val the double to encode
   */
  public static byte[] toByteArray(double val) {
    return toByteArray(Double.doubleToRawLongBits(val));
  }

  /**
   * Encodes a string as a big-endian byte array. The resulting array encodes the length of the string in the first
   * four bytes, then the contents of the string.
   */
  public static byte[] toByteArray(String string) {
    try {
      return Bytes.concat(toByteArray(string.length()), string.getBytes("UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError("UTF-8 is not supported (the JVM is not to spec!)", e);
    }
  }

  /**
   * Encodes a string array as a big-endian byte array. These can be read with {@link #readStringArray(byte[], int)}.
   */
  public static byte[] toByteArray(String[] array) { // NOPMD varargs
    return Serializers.get(StringArrayType.Instance).serialize(array);
  }

  /**
   * Creates a new array with the same contents as {@code raw} in the range {@code (start, end]}. Note: the two arrays
   * are <i>distinct</i>; modifying one will <i>not</i> modify the other.
   *
   * @param raw   the array to get a subarray from
   * @param start the starting index, inclusive, of the subarray in the original
   * @param end   the final index, exclusive, of the subarray in the original
   *
   * @return a new array with the same contents as {@code raw} in the range {@code (start, end]}
   *
   * @deprecated use {@link Arrays#copyOfRange(byte[], int, int)} instead
   */
  @Deprecated
  public static byte[] subArray(byte[] raw, int start, int end) {
    return Arrays.copyOfRange(raw, start, end);
  }

  /**
   * Puts {@code src} into {@code dst} at the given position. This is a shortcut for
   * {@code System.arraycopy(src, 0, dst, pos, stc.length)}.
   *
   * @param dst the array to be copied into
   * @param src the array to copy
   * @param pos the position in {@code dst} to copy {@code src}
   */
  public static void put(byte[] dst, byte[] src, int pos) {
    Objects.requireNonNull(dst, "dst array");
    Objects.requireNonNull(src, "src array");
    System.arraycopy(src, 0, dst, pos, src.length);
  }

  /**
   * Parses a byte array as a boolean value.
   */
  public static boolean readBoolean(byte[] array) {
    return readBoolean(array, 0);
  }

  /**
   * Reads a boolean from a byte array at the given position.
   */
  public static boolean readBoolean(byte[] array, int pos) {
    return array[pos] != 0;
  }

  /**
   * Reads a {@code short} (signed 16-bit integer) from a big-endian byte array.
   */
  public static short readShort(byte[] array) {
    return readShort(array, 0);
  }

  /**
   * Reads a {@code short} (signed 16-bit integer) from a big-endian byte array.
   */
  public static short readShort(byte[] array, int pos) {
    return (short) (((array[pos] & 0xFF) << 8) | (array[pos + 1] & 0xFF));
  }

  /**
   * Reads a 32-bit int from a big-endian byte array.
   */
  public static int readInt(byte[] array) {
    if (array.length != SIZE_OF_INT) {
      throw new IllegalArgumentException("Required 4 bytes, but was given " + array.length);
    }
    return readInt(array, 0);
  }

  /**
   * Reads a 32-bit int from a big-endian byte array.
   */
  public static int readInt(byte[] array, int pos) {
    if (array.length < pos + SIZE_OF_INT) {
      throw new IllegalArgumentException(
          "Not enough bytes to read from. Starting position = " + pos + ", array length = " + array.length);
    }
    return (array[pos] & 0xFF) << 24
        | (array[pos + 1] & 0xFF) << 16
        | (array[pos + 2] & 0xFF) << 8
        | (array[pos + 3] & 0xFF);
  }

  /**
   * Reads a 64-bit int from a big-endian byte array.
   */
  public static long readLong(byte[] array) {
    if (array.length != SIZE_OF_LONG) {
      throw new IllegalArgumentException("Required 8 bytes, but was given " + array.length);
    }
    return readLong(array, 0);
  }

  /**
   * Reads a 64-bit int from a big-endian byte array.
   */
  public static long readLong(byte[] array, int pos) {
    if (array.length < pos + SIZE_OF_LONG) {
      throw new IllegalArgumentException(
          "Not enough bytes to read from. Starting position = " + pos + ", array length = " + array.length);
    }
    return ((long) (array[pos] & 0xFF) << 56)
        | ((long) (array[pos + 1] & 0xFF) << 48)
        | ((long) (array[pos + 2] & 0xFF) << 40)
        | ((long) (array[pos + 3] & 0xFF) << 32)
        | ((long) (array[pos + 4] & 0xFF) << 24)
        | ((long) (array[pos + 5] & 0xFF) << 16)
        | ((long) (array[pos + 6] & 0xFF) << 8)
        | (long) (array[pos + 7] & 0xFF);
  }

  /**
   * Reads a double-precision number from a big-endian byte array.
   */
  public static double readDouble(byte[] array, int pos) {
    return Double.longBitsToDouble(readLong(array, pos));
  }

  /**
   * Reads a string from the given byte array, starting at the given position.
   *
   * @param array the array to read from
   * @param pos   the starting position of the encoded string
   */
  public static String readString(byte[] array, int pos) {
    return Serializers.get(StringType.Instance).deserialize(array, pos);
  }

  /**
   * Reads a string array from the given byte array, starting at the given position.
   *
   * @param array the array to read from
   * @param pos   the starting position of the encoded string array
   */
  public static String[] readStringArray(byte[] array, int pos) {
    return Serializers.get(StringArrayType.Instance).deserialize(array, pos);
  }

}
