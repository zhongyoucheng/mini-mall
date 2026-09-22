# 阶段一：解层（把 fat jar 拆成变化频率不同的目录）
FROM eclipse-temurin:17-jre AS extractor
WORKDIR /build
COPY user-service/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# 阶段二：运行（按依赖→loader→SNAPSHOT→业务 的顺序 COPY，缓存最优）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=extractor /build/extracted/dependencies/ ./
COPY --from=extractor /build/extracted/spring-boot-loader/ ./
COPY --from=extractor /build/extracted/snapshot-dependencies/ ./
COPY --from=extractor /build/extracted/application/ ./
EXPOSE 8081
# 3.2+ 启动器换了包名：org.springframework.boot.loader.launch.JarLauncher
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
