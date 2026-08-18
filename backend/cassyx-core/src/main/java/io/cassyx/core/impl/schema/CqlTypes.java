package io.cassyx.core.impl.schema;

import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.ListType;
import com.datastax.oss.driver.api.core.type.MapType;
import com.datastax.oss.driver.api.core.type.SetType;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.VectorType;

/** Structural questions about a driver {@link DataType} that the info panel and tree ask. */
final class CqlTypes {

  private CqlTypes() {}

  static boolean isCollection(DataType type) {
    return type instanceof ListType || type instanceof SetType || type instanceof MapType;
  }

  static boolean isCounter(DataType type) {
    return DataTypes.COUNTER.equals(type);
  }

  static boolean isFrozen(DataType type) {
    if (type instanceof ListType list) {
      return list.isFrozen();
    }
    if (type instanceof SetType set) {
      return set.isFrozen();
    }
    if (type instanceof MapType map) {
      return map.isFrozen();
    }
    if (type instanceof UserDefinedType udt) {
      return udt.isFrozen();
    }
    return type instanceof TupleType;
  }

  /** True when {@code type} is, or nests, the named UDT - the "why the drop was refused" check. */
  static boolean references(DataType type, String udtName) {
    if (type instanceof UserDefinedType udt) {
      return udt.getName().asInternal().equals(udtName)
          || udt.getFieldTypes().stream().anyMatch(field -> references(field, udtName));
    }
    if (type instanceof MapType map) {
      return references(map.getKeyType(), udtName) || references(map.getValueType(), udtName);
    }
    if (type instanceof ListType list) {
      return references(list.getElementType(), udtName);
    }
    if (type instanceof SetType set) {
      return references(set.getElementType(), udtName);
    }
    if (type instanceof TupleType tuple) {
      return tuple.getComponentTypes().stream().anyMatch(component -> references(component, udtName));
    }
    if (type instanceof VectorType vector) {
      return references(vector.getElementType(), udtName);
    }
    return false;
  }
}
