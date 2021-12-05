package com.example.webfluxkotlin.handler

import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.*
import java.util.Comparator.comparing
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream

@Component
class ExtractHandler: HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val sets = 2

        val maybeSectionIdList = request.queryParam("s")
            .map { it.split(",") }
            .map { it.distinct() }
            .map { it.map(Integer::valueOf) }
            .map { it.filter { sectionId -> sectionId in 1 until (1 shl sets) } }
            .flatMap { if (it.isEmpty()) Optional.empty() else Optional.of(it) }

        return maybeSectionIdList.map { sectionIdList ->
            val range = 0 until sets
            val maxSections = (1 shl sets) - 1
            val sourceRequired: List<Boolean> = judgeRequired(sectionIdList)

            val fluxSuppliers: Flux<Supplier<Flux<String>>> = Flux.just(
                Supplier { Flux.just("hoge", "huga", "foo") },
                Supplier { Flux.just("huga", "foo", "bar") }
            )

            val result = if (sectionIdList.size == maxSections) {
                fluxSuppliers.parallel()
                    .flatMap { it.get() }
                    .sequential()
                    .distinct()
            } else {
                fluxSuppliers
                    .zipWithIterable(sourceRequired)
                    .map { pair -> if (pair.t2) pair.t1 else Supplier { Flux.empty() } }
                    .zipWithIterable(range)
                    .parallel()
                    .map { it.mapT1 { supplier -> supplier.get().collect(Collectors.toSet()) } }
                    .sequential()
                    .sort(comparing { it.t2 })
                    .flatMap { it.t1 }
                    .reduce(Stream.empty<Set<String>>()) { acc, set -> Stream.concat(acc, Stream.of(set)) }
                    .map { it.collect(Collectors.toList()) }
                    .flatMapMany(extractingBy(sectionIdList))
            }

            ServerResponse.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(result.map { it + "\n" }, String::class.java)

        }.orElse(
            ServerResponse.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(BodyInserters.fromValue(""))
        )
    }

    private fun judgeRequired(sectionIdList: List<Int>): List<Boolean> {
        val size = 2
        val range = 1..size

        val filterList: List<List<Int>> = listOf(listOf(1, 3), listOf(2, 3))

        return if (sectionIdList.size != 2) {
            range.map { true }
        } else if (filterList.any(sectionIdList::containsAll)) {
            val mask = filterList.find(sectionIdList::containsAll)?.reduce(Int::and)!!
            range.map { it and mask != 0 }
        } else {
            range.map { true }
        }
    }

    private fun <T> extractingBy(sectionIdList: List<Int>): (List<Set<T>>) -> Flux<T> = {
        val predicate: Predicate<T> = sectionIdList.map(composingPredicate(it[0], it[1]))
            .reduce { p1, p2 -> p1.or(p2) }

        Flux.fromIterable(it.reduce { acc, s -> acc.union(s) })
            .parallel()
            .filter(predicate)
            .sequential()
    }

    private fun <T> composingPredicate(set1: Set<T>, set2: Set<T>): (Int) -> Predicate<T> = { sectionId ->
        when (sectionId) {
            1 -> {
                Predicate { set1.contains(it) && !set2.contains(it) }
            }
            2 -> {
                Predicate { !set1.contains(it) && set2.contains(it) }
            }
            3 -> {
                Predicate { listOf(set1, set2).all { set -> set.contains(it) } }
            }
            else -> {
                Predicate { false }
            }
        }
    }
}
