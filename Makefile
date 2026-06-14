.PHONY: cluster down deploy test restart-test images jars clean

cluster:
	./scripts/create-cluster.sh

down:
	./scripts/delete-cluster.sh

jars:
	./gradlew :gateway:bootJar :operator:bootJar -x test

images: jars
	docker build -f gateway/Dockerfile  -t dynamic-gateway:local          .
	docker build -f operator/Dockerfile -t dynamic-gateway-operator:local .

deploy:
	./scripts/deploy-all.sh

test:
	./scripts/test-routes.sh

restart-test:
	./scripts/restart-gateway-test.sh

clean:
	./gradlew clean
