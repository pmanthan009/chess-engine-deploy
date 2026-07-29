# Use an official JDK runtime
FROM eclipse-temurin:17-jdk-alpine

# Set working directory inside the container
WORKDIR /app

# Copy your Java files into the container
COPY . .

# Compile the Java code
RUN javac WebServer.java

# Run the web server
CMD ["java", "WebServer"]