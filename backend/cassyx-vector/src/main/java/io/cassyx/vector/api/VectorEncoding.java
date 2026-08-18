package io.cassyx.vector.api;

import com.datastax.oss.driver.api.core.data.CqlVector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * Conversions between the driver's {@link CqlVector}, plain {@code List<Float>} and the wire/export
 * forms (plan section 6, "Display" and "Export").
 *
 * <p>Export rules, exactly as the plan states them: <b>CSV and JSON encode a vector as a JSON array
 * of numbers; Parquet uses a native list type.</b> {@link #toJsonArray} is the first; a Parquet
 * writer wants {@link #toFloatList} and should map it to a {@code LIST<FLOAT>} field rather than a
 * string. Both are here so the bulk encoders (workstream D) have one place to call and cannot
 * accidentally serialise 1536 floats as {@code toString()}.
 *
 * <p>Static-only: this is an interface purely so it needs no instances.
 */
public interface VectorEncoding {

  /** Display cap - a sparkline needs far fewer points than a 1536-dimension vector has. */
  int SPARKLINE_SAMPLES = 64;

  /**
   * Coerces any driver representation of a vector into {@code List<Float>}.
   *
   * @return {@code null} when the value is {@code null}
   * @throws VectorException if the value is not a vector-shaped thing
   */
  static List<Float> toFloatList(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof CqlVector<?> vector) {
      List<Float> floats = new ArrayList<>(vector.size());
      for (Object element : vector) {
        floats.add(toFloat(element));
      }
      return List.copyOf(floats);
    }
    if (value instanceof float[] array) {
      List<Float> floats = new ArrayList<>(array.length);
      for (float element : array) {
        floats.add(element);
      }
      return List.copyOf(floats);
    }
    if (value instanceof Collection<?> collection) {
      List<Float> floats = new ArrayList<>(collection.size());
      for (Object element : collection) {
        floats.add(toFloat(element));
      }
      return List.copyOf(floats);
    }
    throw new VectorException(
        "Not a vector value: " + value.getClass().getName());
  }

  /** The driver representation, for binding into a statement. */
  static CqlVector<Float> toCqlVector(List<Float> values) {
    if (values == null) {
      throw new VectorException("Vector values are required");
    }
    if (values.isEmpty()) {
      throw new VectorException("A vector must have at least one dimension");
    }
    return CqlVector.newInstance(List.copyOf(values));
  }

  /** Primitive array, for the similarity maths. */
  static float[] toArray(List<Float> values) {
    if (values == null) {
      throw new VectorException("Vector values are required");
    }
    float[] array = new float[values.size()];
    for (int i = 0; i < array.length; i++) {
      Float element = values.get(i);
      if (element == null) {
        throw new VectorException("The vector contains a null element at index " + i);
      }
      array[i] = element;
    }
    return array;
  }

  /** Euclidean magnitude - the length shown next to the values in the inspector panel. */
  static double magnitude(float[] vector) {
    if (vector == null) {
      throw new VectorException("Vector values are required");
    }
    double sum = 0.0d;
    for (float value : vector) {
      sum += (double) value * (double) value;
    }
    return Math.sqrt(sum);
  }

  /**
   * A JSON array of numbers - the CSV/JSON export encoding and the CQL vector literal, which are
   * the same syntax.
   */
  static String toJsonArray(List<Float> values) {
    if (values == null) {
      return "null";
    }
    StringJoiner joiner = new StringJoiner(", ", "[", "]");
    for (Float value : values) {
      joiner.add(value == null ? "null" : Float.toString(value));
    }
    return joiner.toString();
  }

  /** {@link #toJsonArray} straight from a driver value; the call site bulk encoders want. */
  static String encodeForExport(Object cqlValue) {
    return toJsonArray(toFloatList(cqlValue));
  }

  /**
   * Down-samples a vector to at most {@code samples} points for the grid sparkline, so a cell never
   * ships 1536 floats to the browser just to draw 64 pixels of line.
   */
  static List<Float> sparkline(List<Float> values, int samples) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    int target = Math.max(1, samples);
    if (values.size() <= target) {
      return List.copyOf(values);
    }
    List<Float> sampled = new ArrayList<>(target);
    for (int i = 0; i < target; i++) {
      // Bucket midpoint: cheaper than averaging and keeps the shape of the curve.
      int index = (int) (((long) i * values.size() + values.size() / 2L) / target);
      sampled.add(values.get(Math.min(index, values.size() - 1)));
    }
    return List.copyOf(sampled);
  }

  /** {@link #sparkline(List, int)} at the default resolution. */
  static List<Float> sparkline(List<Float> values) {
    return sparkline(values, SPARKLINE_SAMPLES);
  }

  private static Float toFloat(Object element) {
    if (element == null) {
      throw new VectorException("A vector element is null");
    }
    if (element instanceof Float floatValue) {
      return floatValue;
    }
    if (element instanceof Number number) {
      return number.floatValue();
    }
    throw new VectorException("Vector element is not a number: " + element.getClass().getName());
  }
}
