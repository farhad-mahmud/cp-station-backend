FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

RUN mkdir -p out && javac -cp "lib/*" -d out $(find src -name "*.java")

CMD ["sh", "-c", "java -cp lib/*:out Server"]