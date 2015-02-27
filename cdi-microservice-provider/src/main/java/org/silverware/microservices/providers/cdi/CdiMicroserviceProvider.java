/*
 * -----------------------------------------------------------------------\
 * SilverWare
 *  
 * Copyright (C) 2010 - 2013 the original author or authors.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -----------------------------------------------------------------------/
 */
package org.silverware.microservices.providers.cdi;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.silverware.microservices.Context;
import org.silverware.microservices.annotations.Microservice;
import org.silverware.microservices.providers.MicroserviceProvider;
import org.silverware.microservices.silver.CdiSilverService;
import org.silverware.microservices.util.Utils;

import java.util.Set;
import javax.annotation.Priority;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.ProcessInjectionTarget;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

/**
 * @author Martin Večeřa <marvenec@gmail.com>
 */
public class CdiMicroserviceProvider implements MicroserviceProvider, CdiSilverService {

   private static final Logger log = LogManager.getLogger(CdiMicroserviceProvider.class);

   private Context context;

   @Override
   public void initialize(final Context context) {
      this.context = context;
      //  cheatLoggerProviderToWeld();
   }

   @Override
   public Context getContext() {
      return context;
   }

   @Override
   public void run() {
      try {
         log.info("Hello from CDI microservice provider!");

         final Weld weld = new Weld();
         weld.addExtension(new MicroservicesExtension());

         final WeldContainer container = weld.initialize();
         context.getProperties().put(BEAN_MANAGER, container.getBeanManager());
         context.getProperties().put(CDI_CONTAINER, container);

         container.event().select(MicroservicesStartedEvent.class).fire(new MicroservicesStartedEvent(context, container.getBeanManager(), container));

         try {
            while (!Thread.currentThread().isInterrupted()) {
               Thread.sleep(1000);
            }
         } catch (InterruptedException ie) {
            Utils.shutdownLog(log, ie);
         } finally {
            weld.shutdown();
         }
      } catch (Exception e) {
         log.error("CDI microservice provider failed: ", e);
      }
   }

   public static Object getMicroservice(final Context context, final Class clazz) {
      return ((WeldContainer) context.getProperties().get(CDI_CONTAINER)).instance().select(clazz).get();
   }

   public static final class MicroservicesExtension implements Extension {

      public <T> void injectionTarget(final @Observes ProcessInjectionTarget<T> pit) {
         final AnnotatedType<T> at = pit.getAnnotatedType();

         if (at.isAnnotationPresent(Microservice.class)) {
            log.info("Observed " + pit.getInjectionTarget().toString());

            final InjectionTarget<T> it = pit.getInjectionTarget();
            final InjectionTarget<T> wrapper = new InjectionTarget<T>() {
               @Override
               public void inject(final T instance, final CreationalContext<T> ctx) {
                  log.info("Injecting " + instance.getClass().getName() + " under context " + ctx);
                  it.inject(instance, ctx);
               }

               @Override
               public void postConstruct(final T instance) {
                  it.postConstruct(instance);
               }

               @Override
               public void preDestroy(final T instance) {
                  it.preDestroy(instance);
               }

               @Override
               public T produce(final CreationalContext<T> ctx) {
                  final T t = it.produce(ctx);
                  return MicroserviceProxy.getProxy(t);
               }

               @Override
               public void dispose(final T instance) {
                  it.dispose(instance);
               }

               @Override
               public Set<InjectionPoint> getInjectionPoints() {
                  return it.getInjectionPoints();
               }
            };

            pit.setInjectionTarget(wrapper);
         }
      }

      public void afterBeanDiscovery(final @Observes AfterBeanDiscovery event, BeanManager manager) {
         event.addContext(new MicroserviceContext());
      }
   }

   @Interceptor()
   @Priority(Interceptor.Priority.APPLICATION)
   public static class LoggingInterceptor {

      @AroundInvoke
      public Object log(final InvocationContext ic) throws Exception {
         log.info("AroundInvoke " + ic.toString());
         return ic.proceed();
      }
   }
}