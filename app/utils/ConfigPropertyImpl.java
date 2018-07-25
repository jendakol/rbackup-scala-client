package utils;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.Objects;

@SuppressWarnings("ClassExplicitlyAnnotation")
public class ConfigPropertyImpl implements ConfigProperty, Serializable {

    private final String name;

    public ConfigPropertyImpl(final String name) {
        this.name = name;
    }

    @Override
    public String value() {
        return name;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ConfigPropertyImpl that = (ConfigPropertyImpl) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        // This is specified in java.lang.Annotation.
        return (127 * "value".hashCode()) ^ name.hashCode();
    }

    @Override
    public String toString() {
        return "ConfigProperty{" + name + "}";
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return ConfigProperty.class;
    }

    private static final long serialVersionUID = 0;
}
