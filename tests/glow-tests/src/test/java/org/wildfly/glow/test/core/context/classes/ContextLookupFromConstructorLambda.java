/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2023 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.glow.test.core.context.classes;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.function.Function;

public class ContextLookupFromConstructorLambda {

    private final Context initialContext;

    private static String LOOKUP = "jndi";

    public ContextLookupFromConstructorLambda() throws NamingException {
        this.initialContext = new InitialContext();
        useFunction(x -> {
            try {
                System.out.println(this.getClass().getName());
                return initialContext.lookup(LOOKUP);
            } catch (NamingException e) {
                throw new RuntimeException(e);
            }
        });

    }

    private void useFunction(Function function) {

    }
}
