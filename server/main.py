from typing import Union
from fastapi import FastAPI

# Create an instance of the FastAPI class
app = FastAPI()

# Define a path operation decorator for the root URL ("/") with a GET method
@app.get("/")
def read_root():
    """
    Handles GET requests to the root endpoint.
    """
    return {"Hello": "World"}

# Define another path operation with a path parameter (item_id)
# and an optional query parameter (q)
@app.get("/items/{item_id}")
def read_item(item_id: int, q: Union[str, None] = None):
    """
    Handles GET requests to /items/{item_id}.
    item_id is a path parameter (integer).
    q is an optional query parameter (string).
    """
    return {"item_id": item_id, "q": q}
