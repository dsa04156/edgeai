FROM scratch
LABEL org.opencontainers.image.source=https://github.com/dsa04156/edgeai
COPY output/continuous-vd/bin/processing-agent /processing-agent
USER 10001:10001
EXPOSE 10083
ENTRYPOINT ["/processing-agent"]
