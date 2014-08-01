#!/bin/sh

provisioner --scale 2
provisioner --clean
provisioner --restart

for i in {1..1}
do
coordinator --memberWorkerCount 2 \
	--clientWorkerCount 2 \
	--duration 1m \
	--workerVmOptions "-XX:+HeapDumpOnOutOfMemoryError" \
	--parallel \
	sandBoxTest.properties
done

provisioner --download

echo "The End"