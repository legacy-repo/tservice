FROM openjdk:8-alpine

COPY target/uberjar/tservice.jar /tservice/app.jar

EXPOSE 3000

CMD ["java", "-jar", "/tservice/app.jar"]
