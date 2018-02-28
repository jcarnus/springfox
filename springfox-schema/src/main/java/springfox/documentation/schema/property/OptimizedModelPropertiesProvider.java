/*
 *
 *  Copyright 2015-2016 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package springfox.documentation.schema.property;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.TypeResolver;
import com.fasterxml.classmate.members.ResolvedField;
import com.fasterxml.classmate.members.ResolvedMethod;
import com.fasterxml.classmate.members.ResolvedParameterizedMember;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import springfox.documentation.builders.ModelPropertyBuilder;
import springfox.documentation.schema.ModelProperty;
import springfox.documentation.schema.TypeNameExtractor;
import springfox.documentation.schema.configuration.ObjectMapperConfigured;
import springfox.documentation.schema.plugins.SchemaPluginsManager;
import springfox.documentation.schema.property.bean.AccessorsProvider;
import springfox.documentation.schema.property.bean.BeanModelProperty;
import springfox.documentation.schema.property.bean.ParameterModelProperty;
import springfox.documentation.schema.property.field.FieldModelProperty;
import springfox.documentation.schema.property.field.FieldProvider;
import springfox.documentation.spi.schema.contexts.ModelContext;
import springfox.documentation.spi.schema.contexts.ModelPropertyContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.FluentIterable.from;
import static com.google.common.collect.Iterables.tryFind;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.uniqueIndex;
import static springfox.documentation.schema.Annotations.memberIsUnwrapped;
import static springfox.documentation.schema.ResolvedTypes.modelRefFactory;
import static springfox.documentation.schema.property.BeanPropertyDefinitions.name;
import static springfox.documentation.schema.property.FactoryMethodProvider.factoryMethodOf;
import static springfox.documentation.schema.property.bean.BeanModelProperty.paramOrReturnType;
import static springfox.documentation.spi.schema.contexts.ModelContext.fromParent;

@Primary
@Component("optimized")
public class OptimizedModelPropertiesProvider implements ModelPropertiesProvider {
  private static final Logger LOG = LoggerFactory.getLogger(OptimizedModelPropertiesProvider.class);
  private final AccessorsProvider accessors;
  private final FieldProvider fields;
  private final FactoryMethodProvider factoryMethods;
  private final TypeResolver typeResolver;
  private final BeanPropertyNamingStrategy namingStrategy;
  private final SchemaPluginsManager schemaPluginsManager;
  private final TypeNameExtractor typeNameExtractor;
  private ObjectMapper objectMapper;

  @Autowired
  public OptimizedModelPropertiesProvider(
      AccessorsProvider accessors,
      FieldProvider fields,
      FactoryMethodProvider factoryMethods,
      TypeResolver typeResolver,
      BeanPropertyNamingStrategy namingStrategy,
      SchemaPluginsManager schemaPluginsManager,
      TypeNameExtractor typeNameExtractor) {

    this.accessors = accessors;
    this.fields = fields;
    this.factoryMethods = factoryMethods;
    this.typeResolver = typeResolver;
    this.namingStrategy = namingStrategy;
    this.schemaPluginsManager = schemaPluginsManager;
    this.typeNameExtractor = typeNameExtractor;
  }

  @Override
  public void onApplicationEvent(ObjectMapperConfigured event) {
    objectMapper = event.getObjectMapper();
  }


  @Override
  public List<ModelProperty> propertiesFor(ResolvedType type, ModelContext givenContext) {
    return getModelPropertiesWithPrevious(type, givenContext, null);
  }

  private List<ModelProperty> getModelPropertiesWithPrevious(ResolvedType type, ModelContext givenContext, AnnotatedMember previous) {
    List<ModelProperty> properties = newArrayList();
    BeanDescription beanDescription = beanDescription(type, givenContext);
    Map<String, BeanPropertyDefinition> propertyLookup = uniqueIndex(beanDescription.findProperties(),
        BeanPropertyDefinitions.beanPropertyByInternalName());
    for (Map.Entry<String, BeanPropertyDefinition> each : propertyLookup.entrySet()) {
      LOG.debug("Reading property {}", each.getKey());
      BeanPropertyDefinition jacksonProperty = each.getValue();
      Optional<AnnotatedMember> annotatedMember
          = Optional.fromNullable(safeGetPrimaryMember(jacksonProperty));
      if (annotatedMember.isPresent()) {
        properties.addAll(candidateProperties(type, annotatedMember.get(), jacksonProperty, previous, givenContext));
      }
    }
    return FluentIterable.from(properties).toSortedSet(byPropertyName()).asList();
  }

  private Comparator<ModelProperty> byPropertyName() {
    return new Comparator<ModelProperty>() {
      @Override
      public int compare(ModelProperty first, ModelProperty second) {
        return first.getName().compareTo(second.getName());
      }
    };
  }

  private AnnotatedMember safeGetPrimaryMember(BeanPropertyDefinition jacksonProperty) {
    try {
      return jacksonProperty.getPrimaryMember();
    } catch (IllegalArgumentException e) {
      LOG.warn(String.format("Unable to get unique property. %s", e.getMessage()));
      return null;
    }
  }

  private Function<ResolvedMethod, List<ModelProperty>> propertyFromBean(
      final ModelContext givenContext,
      final BeanPropertyDefinition jacksonProperty, final AnnotatedMember previous) {

    return new Function<ResolvedMethod, List<ModelProperty>>() {
      @Override
      public List<ModelProperty> apply(ResolvedMethod input) {
        ResolvedType type = paramOrReturnType(typeResolver, input);
        if (!givenContext.canIgnore(type) && shouldBeIncludeDueToJsonView(jacksonProperty, givenContext)) {
          if (memberIsUnwrapped(jacksonProperty.getPrimaryMember())) {
            return getModelPropertiesWithPrevious(type, fromParent(givenContext, type), jacksonProperty.getPrimaryMember());
          }
          return newArrayList(beanModelProperty(input, jacksonProperty, previous, givenContext));
        }
        return newArrayList();
      }
    };
  }


  private Function<ResolvedField, List<ModelProperty>> propertyFromField(
      final ModelContext givenContext,
      final BeanPropertyDefinition jacksonProperty, final AnnotatedMember previous) {

    return new Function<ResolvedField, List<ModelProperty>>() {
      @Override
      public List<ModelProperty> apply(ResolvedField input) {
        if (!givenContext.canIgnore(input.getType()) && shouldBeIncludeDueToJsonView(jacksonProperty, givenContext)) {
          AnnotatedField jacksonPropertyField = jacksonProperty.getField();
          if (memberIsUnwrapped(jacksonPropertyField)) {
            return getModelPropertiesWithPrevious(input.getType(),
                ModelContext.fromParent(givenContext, input.getType()), jacksonPropertyField);
          }
          return newArrayList(fieldModelProperty(input, jacksonProperty, previous, givenContext));
        }
        return newArrayList();
      }
    };
  }

  private Predicate<? super Annotation> ofType(final Class<?> annotationType) {
    return new Predicate<Annotation>() {
      @Override
      public boolean apply(Annotation input) {
        return annotationType.isAssignableFrom(input.getClass());
      }
    };
  }

  @VisibleForTesting
  List<ModelProperty> candidateProperties(
      ResolvedType type,
      AnnotatedMember member,
      BeanPropertyDefinition jacksonProperty,
      AnnotatedMember previous,
      ModelContext givenContext) {

    List<ModelProperty> properties = newArrayList();
    if (member instanceof AnnotatedMethod) {
      properties.addAll(findAccessorMethod(type, member)
          .transform(propertyFromBean(givenContext, jacksonProperty, previous))
          .or(new ArrayList<ModelProperty>()));
    } else if (member instanceof AnnotatedField) {
      properties.addAll(findField(type, jacksonProperty.getInternalName())
          .transform(propertyFromField(givenContext, jacksonProperty, previous))
          .or(new ArrayList<ModelProperty>()));
    } else if (member instanceof AnnotatedParameter) {
      ModelContext modelContext = ModelContext.fromParent(givenContext, type);
      properties.addAll(fromFactoryMethod(type, jacksonProperty, (AnnotatedParameter) member, modelContext, previous));
    }
    return from(properties).filter(hiddenProperties()).toList();

  }

  private boolean shouldBeIncludeDueToJsonView(BeanPropertyDefinition jacksonProperty,
                                               ModelContext givenContext) {

    Class classToRestrictOn = null;
    JsonView jsonView = givenContext.getJsonView();
    if (jsonView != null) {
      classToRestrictOn = jsonView.value()[0];
    }
    List<Class> expectedClasses = getAllClasses(classToRestrictOn, new ArrayList<Class>());

    if (expectedClasses.isEmpty()) {
      return true;
    } else {
      Class[] views = jacksonProperty.findViews();
      if (views != null) {
        List<Class<?>> fieldViews = Arrays.asList(jacksonProperty.findViews());
        for (Class eachView : fieldViews) {
          if (expectedClasses.contains(eachView)) {
            return true;
          }
        }
      }
    }

    return false;
  }

  private static List<Class> getAllClasses(Class clazz, List<Class> collectedClasses) {

    // Already recurse on it.
    if (clazz == null || collectedClasses.contains(clazz)) {
      return collectedClasses;
    }

    collectedClasses.add(clazz);

    Class[] nextClasses;

    // If the class is an interface, we get all extended interfaces.
    if (clazz.isInterface()) {
      nextClasses = clazz.getInterfaces();
    } else {
      // Otherwise we get the only extended class.
      nextClasses = new Class[]{clazz.getSuperclass()};
    }

    for (Class currentClass : nextClasses) {
      getAllClasses(currentClass, collectedClasses);
    }
    return collectedClasses;
  }

  private Predicate<? super ModelProperty> hiddenProperties() {
    return new Predicate<ModelProperty>() {
      @Override
      public boolean apply(ModelProperty input) {
        return !input.isHidden();
      }
    };
  }

  private Optional<ResolvedField> findField(
      ResolvedType resolvedType,
      final String fieldName) {

    return tryFind(fields.in(resolvedType), new Predicate<ResolvedField>() {
      public boolean apply(ResolvedField input) {
        return fieldName.equals(input.getName());
      }
    });
  }

  private ModelProperty fieldModelProperty(
      ResolvedField childField,
      BeanPropertyDefinition jacksonProperty,
      AnnotatedMember previous,
      ModelContext modelContext) {
    String fieldName = name(jacksonProperty, modelContext.isReturnType(), namingStrategy, previous);
    FieldModelProperty fieldModelProperty
        = new FieldModelProperty(
        fieldName,
        childField,
        typeResolver,
        modelContext.getAlternateTypeProvider(),
        jacksonProperty);
    ModelPropertyBuilder propertyBuilder = new ModelPropertyBuilder()
        .name(fieldModelProperty.getName())
        .type(fieldModelProperty.getType())
        .qualifiedType(fieldModelProperty.qualifiedTypeName())
        .position(fieldModelProperty.position())
        .required(fieldModelProperty.isRequired())
        .description(fieldModelProperty.propertyDescription())
        .allowableValues(fieldModelProperty.allowableValues())
        .example(fieldModelProperty.example());
    return schemaPluginsManager.property(
        new ModelPropertyContext(propertyBuilder,
            childField.getRawMember(),
            typeResolver,
            modelContext.getDocumentationType()))
        .updateModelRef(modelRefFactory(modelContext, typeNameExtractor));
  }

  private ModelProperty beanModelProperty(
      ResolvedMethod childProperty,
      BeanPropertyDefinition jacksonProperty,
      AnnotatedMember previous,
      ModelContext modelContext) {


    String propertyName = name(jacksonProperty, modelContext.isReturnType(), namingStrategy, previous);
    BeanModelProperty beanModelProperty
        = new BeanModelProperty(
        propertyName,
        childProperty,
        typeResolver,
        modelContext.getAlternateTypeProvider(),
        jacksonProperty);

    LOG.debug("Adding property {} to model", propertyName);
    ModelPropertyBuilder propertyBuilder = new ModelPropertyBuilder()
        .name(beanModelProperty.getName())
        .type(beanModelProperty.getType())
        .qualifiedType(beanModelProperty.qualifiedTypeName())
        .position(beanModelProperty.position())
        .required(beanModelProperty.isRequired())
        .isHidden(false)
        .description(beanModelProperty.propertyDescription())
        .allowableValues(beanModelProperty.allowableValues())
        .example(beanModelProperty.example());
    return schemaPluginsManager.property(
        new ModelPropertyContext(propertyBuilder,
            jacksonProperty,
            typeResolver,
            modelContext.getDocumentationType()))
        .updateModelRef(modelRefFactory(modelContext, typeNameExtractor));
  }


  private ModelProperty paramModelProperty(
      ResolvedParameterizedMember constructor,
      BeanPropertyDefinition jacksonProperty,
      AnnotatedParameter parameter,
      ModelContext modelContext, AnnotatedMember previous) {

    String propertyName = name(jacksonProperty, modelContext.isReturnType(), namingStrategy, previous);
    ParameterModelProperty parameterModelProperty
        = new ParameterModelProperty(
        propertyName,
        parameter,
        constructor,
        typeResolver,
        modelContext.getAlternateTypeProvider(),
        jacksonProperty);

    LOG.debug("Adding property {} to model", propertyName);
    ModelPropertyBuilder propertyBuilder = new ModelPropertyBuilder()
        .name(parameterModelProperty.getName())
        .type(parameterModelProperty.getType())
        .qualifiedType(parameterModelProperty.qualifiedTypeName())
        .position(parameterModelProperty.position())
        .required(parameterModelProperty.isRequired())
        .isHidden(false)
        .description(parameterModelProperty.propertyDescription())
        .allowableValues(parameterModelProperty.allowableValues())
        .example(parameterModelProperty.example());
    return schemaPluginsManager.property(
        new ModelPropertyContext(propertyBuilder,
            jacksonProperty,
            typeResolver,
            modelContext.getDocumentationType()))
        .updateModelRef(modelRefFactory(modelContext, typeNameExtractor));
  }

  private Optional<ResolvedMethod> findAccessorMethod(ResolvedType resolvedType, final AnnotatedMember member) {
    return tryFind(accessors.in(resolvedType), new Predicate<ResolvedMethod>() {
      public boolean apply(ResolvedMethod accessorMethod) {
        SimpleMethodSignatureEquality methodComparer = new SimpleMethodSignatureEquality();
        return methodComparer.equivalent(accessorMethod.getRawMember(), (Method) member.getMember());
      }
    });
  }

  private List<ModelProperty> fromFactoryMethod(
      final ResolvedType resolvedType,
      final BeanPropertyDefinition beanProperty,
      final AnnotatedParameter member,
      final ModelContext givenContext, final AnnotatedMember previous) {

    Optional<ModelProperty> property = factoryMethods.in(resolvedType, factoryMethodOf(member))
        .transform(new Function<ResolvedParameterizedMember, ModelProperty>() {
          @Override
          public ModelProperty apply(ResolvedParameterizedMember input) {
            return paramModelProperty(input, beanProperty, member, givenContext, previous);
          }
        });
    if (property.isPresent()) {
      return newArrayList(property.get());
    }
    return newArrayList();
  }

  private BeanDescription beanDescription(ResolvedType type, ModelContext context) {
    if (context.isReturnType()) {
      SerializationConfig serializationConfig = objectMapper.getSerializationConfig();
      return serializationConfig.introspect(serializationConfig.constructType(type.getErasedType()));
    } else {
      DeserializationConfig serializationConfig = objectMapper.getDeserializationConfig();
      return serializationConfig.introspect(serializationConfig.constructType(type.getErasedType()));
    }
  }
}
