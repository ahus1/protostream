package org.infinispan.protostream.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An optional annotation for specifying the Protobuf enum type name.
 *
 * @author anistor@redhat.com
 * @since 3.0
 * @deprecated replaced by {@link ProtoName}
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Deprecated
public @interface ProtoEnum {

   /**
    * Defines the name of the Protobuf enum type. If missing, the Java class name will be used for Protobuf too.
    */
   String name() default "";
}
