TARGET_DIR = target/generated-sources/cpp
PROTO_DIR = src/main/protobuf

INCLUDES = -I$(TARGET_DIR) 

CXX = g++
CXXFLAGS = -g $(INCLUDES)

#-----File Dependencies----------------------
PROTO = PubSubProtocol.proto
SRC = $(TARGET_DIR)/$/$(PROTO_DIR)/$(addsuffix .pb.cc, $(basename $(PROTO)))
OBJ = $(addsuffix .o, $(basename $(SRC)))

all: $(OBJ)

$(SRC):
	mkdir -p $(TARGET_DIR); protoc --cpp_out=$(TARGET_DIR) $(PROTO_DIR)/$(PROTO)

$(OBJ): $(SRC)
	$(CXX) $(CXXFLAGS) -c $< -o $(OBJ)


clean:
	rm -rf $(TARGET_DIR)


