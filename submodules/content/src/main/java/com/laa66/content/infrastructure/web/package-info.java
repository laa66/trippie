/*
 * This package holds no types yet -- the first controller arrives in M4-06 -- and this file exists
 * for two reasons that are both easy to delete by accident:
 *
 * 1. It is the anchor that keeps ArchitectureTest's "the HTTP boundary cannot reach an outbound
 *    port" rule from going vacuous. javac emits package-info.class ONLY for a package whose
 *    package-info.java carries an annotation, and ArchUnit 1.5.1 enables failOnEmptyShould by
 *    default -- so with this file, or merely its annotation, gone, the rule fails on an empty class
 *    set rather than passing quietly. Measured.
 *
 * 2. @NullMarked is the least arbitrary annotation to anchor it with. Nothing in this repo enforces
 *    it (no NullAway, no ErrorProne), so it declares an intent for the package rather than a
 *    checked contract -- but it is the intent the first controller would want anyway, and inventing
 *    an annotation of our own purely to hold a class file would be worse.
 *
 * The file is expected to go away in M4-06, when real classes in this package take over both jobs.
 *
 * jspecify is declared compileOnly in build.gradle.kts. That declaration is hygiene, not a repair:
 * measured, the file compiles without it too, because spring-core ships org.jspecify:jspecify in
 * compile scope -- the point is that the annotation we write against is declared by us. compileOnly
 * also does not mean jspecify is absent at runtime: spring-core requires it on the runtime
 * classpath, so excluding org.jspecify breaks context startup (measured).
 */
@NullMarked
package com.laa66.content.infrastructure.web;

import org.jspecify.annotations.NullMarked;
